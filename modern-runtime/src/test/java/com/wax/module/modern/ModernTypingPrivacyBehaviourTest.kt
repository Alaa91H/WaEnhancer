package com.wax.module.modern

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A requested privacy setting and an installed hook are NOT externally
 * observed suppression. Startup must not report WITHHELD or VERIFIED (#450).
 */
class ModernTypingPrivacyBehaviourTest {
    private class FakePreferences(
        private val booleans: Map<String, Boolean> = emptyMap(),
        private val strings: Map<String, String> = emptyMap(),
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> =
            HashMap<String, Any>().apply {
                putAll(booleans)
                putAll(strings)
            }

        override fun getString(key: String, defValue: String?): String? =
            strings[key] ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(key: String, defValue: Int): Int = defValue

        override fun getLong(key: String, defValue: Long): Long = defValue

        override fun getFloat(key: String, defValue: Float): Float = defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            booleans[key] ?: defValue

        override fun contains(key: String): Boolean =
            key in booleans || key in strings

        override fun edit(): SharedPreferences.Editor =
            throw UnsupportedOperationException("read-only fixture")

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Test
    fun installedHookWithToggleIsOnlyUnverifiedEvidence() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(booleans = mapOf("ghostmode_t" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.typingRequested)
        assertFalse(state.recordingRequested)
        assertFalse(state.customRulesRequested)
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.typingTelemetryState())
        assertEquals("DISABLED", state.recordingTelemetryState())
    }

    @Test
    fun requestedSwitchWithoutHookIsNotSuppressionProof() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(booleans = mapOf("ghostmode_t" to true)),
                ModernTypingPrivacyFeature.Outcome.RESOLVER_MISSING,
            )
        assertTrue(state.typingRequested)
        assertEquals("REQUESTED_NOT_INSTALLED", state.typingTelemetryState())
        assertEquals("DISABLED", state.recordingTelemetryState())
    }

    @Test
    fun typingAndRecordingAreReportedSeparately() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(booleans = mapOf("ghostmode_r" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.recordingRequested)
        assertFalse(state.typingRequested)
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.recordingTelemetryState())
        assertEquals("DISABLED", state.typingTelemetryState())
    }

    @Test
    fun ghostModeRequestsBothButProvesNeither() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(booleans = mapOf("ghostmode" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.typingRequested)
        assertTrue(state.recordingRequested)
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.typingTelemetryState())
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.recordingTelemetryState())
    }

    @Test
    fun noRequestIsDisabledEvenIfHookRegisteredForAnotherReason() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertEquals("DISABLED", state.typingTelemetryState())
        assertEquals("DISABLED", state.recordingTelemetryState())
    }

    @Test
    fun customOnlyModeIsNotMisreportedAsDisabled() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(strings = mapOf("custom_privacy_type" to "2")),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertFalse(state.typingRequested)
        assertFalse(state.recordingRequested)
        assertTrue(state.customRulesRequested)
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.typingTelemetryState())
        assertEquals("HOOK_INSTALLED_UNVERIFIED", state.recordingTelemetryState())
    }

    @Test
    fun customOnlyModeWithoutResolverIsNotReportedAsHooked() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(strings = mapOf("custom_privacy_type" to "1")),
                ModernTypingPrivacyFeature.Outcome.UNSAFE_SIGNATURE,
            )
        assertEquals("REQUESTED_NOT_INSTALLED", state.typingTelemetryState())
        assertEquals("REQUESTED_NOT_INSTALLED", state.recordingTelemetryState())
    }

    @Test
    fun unsupportedCustomModeNeverCreatesAFalseRequest() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(strings = mapOf("custom_privacy_type" to "invalid")),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertFalse(state.customRulesRequested)
        assertEquals("DISABLED", state.typingTelemetryState())
        assertEquals("DISABLED", state.recordingTelemetryState())
    }

    @Test
    fun suppressionDecisionIsUnchangedByTelemetryCorrection() {
        assertTrue(ModernTypingPrivacyFeature.shouldSuppress(0, true, false))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(0, false, true))
        assertTrue(ModernTypingPrivacyFeature.shouldSuppress(1, false, true))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(1, true, false))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(0, false, false))
        assertEquals(0, ModernTypingPrivacyFeature.STATE_TYPING)
        assertEquals(1, ModernTypingPrivacyFeature.STATE_RECORDING)
    }
}
