package com.wax.module.modern

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Reads the existing legacy per-contact overrides from the CURRENT target's
 * private WaGlobal preferences. CustomPrivacy saves these records inside
 * WhatsApp, not in the WA-X Manager's default preferences.
 *
 * No Binder call, contact-number transfer, global singleton or persistent
 * in-process cache: a saved override is visible on the next composing event
 * in the process that owns those SharedPreferences.
 */
internal class ModernTargetPrivacyRuleStore(
    private val preferences: SharedPreferences,
    private val decoder: (String) -> ModernTypingPrivacyFeature.PrivacyRule = ::decode,
) {
    fun forPhoneNumber(number: String?): ModernTypingPrivacyFeature.PrivacyRule? {
        if (!isValidPhoneNumber(number)) return null
        val raw =
            try {
                preferences.getString("${number}_privacy", null)
            } catch (_: ClassCastException) {
                return failClosed()
            } ?: return null
        if (raw.length > MAX_RULE_BYTES) return failClosed()
        return try {
            decoder(raw)
        } catch (_: Exception) {
            // An unreadable existing override may hide rather than leak the
            // first composing event. Never log its contact key or content.
            failClosed()
        }
    }

    companion object {
        private const val MAX_RULE_BYTES = 4096

        @JvmStatic
        fun isValidPhoneNumber(number: String?): Boolean =
            !number.isNullOrEmpty() &&
                number.length <= 32 &&
                number.all { it in '0'..'9' }

        private fun failClosed() = ModernTypingPrivacyFeature.PrivacyRule(true, true)

        private fun decode(raw: String): ModernTypingPrivacyFeature.PrivacyRule {
            val json = JSONObject(raw)
            fun optionalBoolean(key: String): Boolean? =
                if (!json.has(key)) {
                    null
                } else {
                    // The legacy settings UI stores real JSON booleans.
                    // Unknown types are corrupted overrides: fail closed.
                    json.opt(key) as? Boolean ?: true
                }
            return ModernTypingPrivacyFeature.PrivacyRule(
                hideTyping = optionalBoolean("HideTyping"),
                hideRecording = optionalBoolean("HideRecording"),
            )
        }
    }
}
