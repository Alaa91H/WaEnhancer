package com.wax.module.status

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.wax.module.platform.TargetApp

/**
 * Where the editor gets the two facts it cannot assume: what this client posts, and how long for.
 *
 * Both are read rather than hardcoded, because both are per-target facts:
 *
 * - **The limit.** WhatsApp's voice Status length is a per-version value. A constant would either
 *   refuse a clip the client would post, or promise a post it will reject. The reader returns
 *   [StatusAudioCapability.Unknown] when it cannot tell, and the planner then records that it
 *   used the compatibility fallback. A fallback is shown to the user as a fallback.
 * - **The containers.** Whether this build posts Opus and OGG natively is also a property of the
 *   build, not of the module.
 *
 * Nothing here guesses. There is no path today that fills the limit from a constant and calls it
 * a reading, because the distinction between "read from the client" and "assumed" is the one the
 * whole compatibility matrix turns on.
 */
object StatusAudioCapabilityReader {
    /**
     * The value a target records for its voice Status limit, in milliseconds.
     *
     * Written by the runtime when it has read the value from the client; absent means unknown,
     * and unknown must stay unknown rather than become a constant.
     */
    const val LIMIT_PREFERENCE_KEY = "wax.status.voice_limit_ms"

    /** The containers a target records as natively postable, comma separated. */
    const val CONTAINERS_PREFERENCE_KEY = "wax.status.voice_containers"

    /**
     * Reads what [target] has recorded, from a store scoped to that target.
     *
     * Scoping matters: a limit learned from WhatsApp says nothing about Business, and a feature
     * that read one target's value while showing another's settings would promise a post the
     * second target will reject.
     */
    fun read(
        store: SharedPreferences,
        target: TargetApp,
    ): StatusAudioCapability {
        val prefix = target.packageName
        val limit =
            store
                .getLong("$prefix.$LIMIT_PREFERENCE_KEY", 0L)
                .takeIf { it > 0L }
                ?: return StatusAudioCapability.Unknown
        val containers =
            store
                .getString("$prefix.$CONTAINERS_PREFERENCE_KEY", null)
                ?.split(',')
                ?.mapNotNull { name -> AudioContainer.entries.firstOrNull { it.name == name.trim() } }
                ?.toSet()
                ?: AudioContainer.entries.toSet()
        return StatusAudioCapability.resolved(limit, containers)
    }

    /**
     * Reads from the default preferences of [context] for [target].
     *
     * This is the call the editor makes. It exists so the editor holds no storage logic of its
     * own and a test can pass a store instead.
     */
    fun read(
        context: Context,
        target: TargetApp,
    ): StatusAudioCapability = read(PreferenceManager.getDefaultSharedPreferences(context), target)

    /** The state of the capability as one line, for diagnostics. */
    fun describe(capability: StatusAudioCapability): String =
        if (capability.limitResolved) {
            "read from ${capability.supportedContainers.size} container(s), ${capability.maxVoiceStatusMillis}ms"
        } else {
            "unknown; the planner will use the ${FALLBACK_MILLIS}ms fallback"
        }

    /**
     * The limit used when nothing could be read, named once for the editor to show.
     *
     * It is the planner's own fallback, referenced rather than restated, so a change to the
     * planner cannot leave the screen promising a different number.
     */
    const val FALLBACK_MILLIS: Long = StatusAudioCapability.FALLBACK_MAX_MILLIS
}
