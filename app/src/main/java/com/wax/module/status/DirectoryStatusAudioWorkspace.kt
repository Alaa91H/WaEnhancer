package com.wax.module.status

import java.io.File

/**
 * Prepares files inside one directory the app already owns.
 *
 * Every name is made here, from the plan's own label and index, so nothing derived from the
 * source file can reach the file system. A file called \`../../shared_prefs/module_xposed.xml\` is
 * reduced to \`wae-status-part-1-of-3.m4a\`: the label is not a name, it is a caption.
 *
 * The directory is not created until a part is allocated, and a preparation that fails leaves
 * nothing behind — the caller deletes the files it was told about, and this class deletes the
 * directory when the last one goes.
 */
class DirectoryStatusAudioWorkspace(
    private val directory: File,
    private val extension: (AudioContainer) -> String,
) : StatusAudioWorkspace {
    /** Paths handed out so far, so [discard] can tell a part of this run from anything else. */
    private val allocated = LinkedHashSet<String>()

    override fun allocate(
        part: StatusAudioSegment,
        container: AudioContainer,
    ): String? {
        val suffix = extension(container)
        if (suffix.isBlank() || suffix.any { !it.isLetterOrDigit() }) return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val name = "wae-status-part-${part.index}-of-${part.total}.$suffix"
        val file = File(directory, name)
        // `File(directory, name)` with a name this function builds cannot escape, and the
        // check is kept so a future change to the pattern cannot make it possible silently.
        if (file.parentFile?.canonicalPath != directory.canonicalPath) return null
        allocated.add(file.absolutePath)
        return file.absolutePath
    }

    override fun discard(path: String) {
        if (path !in allocated) return
        allocated.remove(path)
        runCatching { File(path).delete() }
        if (allocated.isEmpty()) {
            runCatching { directory.delete() }
        }
    }

    /** Releases anything left over, for a process that is going away. */
    fun releaseAll() {
        allocated.toList().forEach { discard(it) }
    }
}
