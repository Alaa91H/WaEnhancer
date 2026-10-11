package com.wax.module.status

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import java.io.File

/**
 * Reads a picked document with the storage framework and the media framework.
 *
 * The pick is copied in before anything else. A SAF grant is for the process that received it,
 * and the studio may be resumed after the grant is gone; working on a copy inside the app's own
 * directory keeps the preparation from failing halfway through a long split because a
 * permission lapsed.
 *
 * No path from the device is returned to the caller. [copyIn] answers with the path of the copy
 * the app made, and [describe] answers with a value whose only name field is a display name.
 */
class AndroidStatusAudioSourceReader(
    private val context: Context,
) : StatusAudioSourceReader {
    private val directory: File by lazy { File(context.cacheDir, "wae-status-audio") }

    /**
     * Why the last [describe] could not read the file, for the editor to show.
     *
     * It is carried on the reader rather than logged: the user is the one who needs to know that
     * the file was refused, and a log line would be read by nobody.
     */
    var unreadableReason: String? = null
        private set

    override fun copyIn(
        uri: String,
        displayName: String,
    ): String? =
        runCatching {
            val name = StatusAudioSourceReaderSupport.safeName(displayName)
            if (!directory.exists() && !directory.mkdirs()) return null
            val target = File(directory, "source-$name")
            context.contentResolver.openInputStream(uri.toUri())?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, BUFFER_BYTES) }
            } ?: return null
            target.absolutePath
        }.getOrNull()

    override fun describe(path: String): StatusAudioSource {
        val file = File(path)
        unreadableReason = null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            StatusAudioSourceReaderSupport.source(
                displayName = file.name,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                durationMillis = retriever.durationMillis(),
                sizeBytes = file.length(),
                hasLocationTag = retriever.hasLocationTag(),
                hasAuthorTag = retriever.hasAuthorTag(),
            )
        } catch (failure: Exception) {
            // A file the framework cannot open still has to be described honestly: the length
            // is unreadable, which the planner rejects, and the name is the only thing known.
            // The reason travels in the returned value rather than to a log, so the editor can
            // show why the file was refused instead of only that it was.
            unreadableReason = failure.message ?: failure.javaClass.simpleName
            StatusAudioSourceReaderSupport.source(
                displayName = file.name,
                mimeType = null,
                durationMillis = 0L,
                sizeBytes = file.length(),
                hasLocationTag = false,
                hasAuthorTag = false,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun discard(path: String) {
        runCatching { File(path).delete() }
        if (directory.listFiles().isNullOrEmpty()) {
            runCatching { directory.delete() }
        }
    }

    private fun MediaMetadataRetriever.durationMillis(): Long =
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    /** Whether the file carries a place. Stripped by default, and never written out. */
    private fun MediaMetadataRetriever.hasLocationTag(): Boolean = extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null

    /** Whether the file names a person or device. Stripped by default, and never written out. */
    private fun MediaMetadataRetriever.hasAuthorTag(): Boolean =
        sequenceOf(
            MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            MediaMetadataRetriever.METADATA_KEY_WRITER,
            MediaMetadataRetriever.METADATA_KEY_ARTIST,
        ).any { extractMetadata(it)?.isNotBlank() == true }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
