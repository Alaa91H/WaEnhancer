package com.wax.module.status

/**
 * How a picked file becomes a [StatusAudioSource], and how a source becomes a working file.
 *
 * The editor never keeps the path the user chose. A SAF picker hands back a content URI that
 * grants access to one document, and the studio works on a copy inside its own directory: the
 * access grant does not outlive the process, and a path that survives would outlive the
 * permission that justified it. Nothing here logs a path, and [StatusAudioSource] carries a
 * display name only.
 *
 * The reader is an interface because the values it produces are what every later decision is
 * made from, and those decisions must be testable without a media framework.
 */
interface StatusAudioSourceReader {
    /** Reads [uri] into the app's own directory, or returns null when it cannot be read. */
    fun copyIn(
        uri: String,
        displayName: String,
    ): String?

    /** Describes a copied file. */
    fun describe(path: String): StatusAudioSource

    /** Deletes a copy. */
    fun discard(path: String)
}

/**
 * The facts the planner needs from a file, read with the media framework.
 *
 * Every field is optional because every one of them can fail to be read: an unreadable length is
 * `0` and is rejected downstream rather than guessed, and a tag that cannot be read is `false`,
 * which strips rather than keeps. Guessing either one would post something the user did not
 * choose.
 */
object StatusAudioSourceReaderSupport {
    /** The size a file larger than this is flagged for, before anything is copied or written. */
    const val LARGE_SOURCE_BYTES: Long = 64L * 1024L * 1024L

    /** Whether [sizeBytes] is worth warning about. */
    @JvmStatic
    fun isLarge(sizeBytes: Long): Boolean = sizeBytes > LARGE_SOURCE_BYTES

    /**
     * Builds a source from what the framework reported.
     *
     * The file name is the last path segment with nothing but its extension kept, so a name
     * like `/data/user/0/com.whatsapp/shared_prefs/WaGlobal.xml` becomes `WaGlobal.xml` and
     * cannot carry a directory into the UI or a log.
     */
    @JvmStatic
    fun source(
        displayName: String,
        mimeType: String?,
        durationMillis: Long,
        sizeBytes: Long,
        hasLocationTag: Boolean,
        hasAuthorTag: Boolean,
    ): StatusAudioSource {
        val name = safeName(displayName)
        val container =
            AudioContainer.fromFileName(name)
                ?: mimeType?.let { AudioContainer.fromMimeType(it) }
        return StatusAudioSource(
            fileName = name,
            container = container,
            durationMillis = durationMillis.coerceAtLeast(0L),
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            hasLocationTag = hasLocationTag,
            hasAuthorTag = hasAuthorTag,
        )
    }

    /**
     * The display name, reduced to something that cannot be a path.
     *
     * A name that is empty, that is only an extension, or that holds a separator becomes
     * `audio.m4a`-style generic rather than anything the source chose. This is the last place a
     * path could enter the UI, so it is where it is stopped.
     */
    @JvmStatic
    fun safeName(displayName: String?): String {
        val base =
            displayName
                ?.trim()
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
        if (base.isBlank() || base == "." || base == "..") return "audio"
        if (base.any { it.code < 0x20 }) return "audio"
        // A name that looks like a path segment rather than a file name is refused outright
        // rather than cleaned, because a cleaned path is a guess about what the user picked.
        return base.take(96)
    }

    /** The extension a container is written with, for a prepared part's name. */
    @JvmStatic
    fun suffixFor(container: AudioContainer): String =
        when (container) {
            AudioContainer.MP3 -> "mp3"
            AudioContainer.M4A -> "m4a"
            AudioContainer.AAC -> "aac"
            AudioContainer.OGG -> "ogg"
            AudioContainer.OPUS -> "opus"
            AudioContainer.WAV -> "wav"
            AudioContainer.FLAC -> "flac"
        }
}
