package com.wax.module.modern

/**
 * One replaceable per-adapter hook. A failed unhook must never be forgotten:
 * otherwise a new adapter can install a second live hook while the first
 * cannot be removed. This slot owns only the raw hook handle (no Activity,
 * message, adapter or JID references).
 */
internal class ModernReplaceableHookSlot {
    private var installed: ModernHookRegistry.Handle? = null

    /** Null means cleanup succeeded or there was no handle to remove. */
    @Synchronized
    fun release(): Throwable? {
        val handle = installed ?: return null
        return try {
            handle.unhook()
            installed = null
            null
        } catch (failure: Throwable) {
            // Retain the *same* handle so a later lifecycle callback can retry.
            // In particular, a failed remove cannot claim the slot is empty.
            failure
        }
    }

    @Synchronized
    fun track(handle: ModernHookRegistry.Handle) {
        check(installed == null) { "Cannot replace a hook whose removal is unconfirmed" }
        installed = handle
    }

    @Synchronized
    fun hasTrackedHandle(): Boolean = installed != null
}
