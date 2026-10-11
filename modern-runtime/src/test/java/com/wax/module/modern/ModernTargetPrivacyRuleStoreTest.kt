package com.wax.module.modern

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fully offline contract checks for the existing target-owned WaGlobal store. */
class ModernTargetPrivacyRuleStoreTest {
    private val number = "491234567890"

    @Test
    fun parsesRealLegacyJsonBooleanOverrides() {
        val values = mutableMapOf<String, Any?>(
            "${number}_privacy" to """{"HideTyping":false,"HideRecording":true}""",
        )
        val store = ModernTargetPrivacyRuleStore(fakePreferences(values))
        val rule = store.forPhoneNumber(number)
        assertEquals(false, rule?.hideTyping)
        assertEquals(true, rule?.hideRecording)
        values["${number}_privacy"] = """{"HideTyping":true}"""
        val newRule = store.forPhoneNumber(number)
        assertEquals(true, newRule?.hideTyping)
        assertNull(newRule?.hideRecording)
    }

    @Test
    fun firstComposingEventReadsSavedOverrideImmediately() {
        val values = mutableMapOf<String, Any?>("${number}_privacy" to "explicit-disable")
        var fetches = 0
        val store =
            ModernTargetPrivacyRuleStore(fakePreferences(values)) {
                fetches++
                ModernTypingPrivacyFeature.PrivacyRule(hideTyping = false, hideRecording = true)
            }
        val saved = store.forPhoneNumber(number)
        assertEquals(1, fetches)
        assertEquals(false, saved?.hideTyping)
        assertEquals(true, saved?.hideRecording)
        // An explicit per-contact false overrides a global typing toggle,
        // exactly as legacy JSONObject.optBoolean(key, globalDefault) did.
        assertFalse(
            ModernTypingPrivacyFeature.shouldSuppressWithOverrides(
                ModernTypingPrivacyFeature.STATE_TYPING,
                globalGhost = false,
                globalTyping = true,
                globalRecording = false,
                rule = saved,
            ),
        )
        assertTrue(
            ModernTypingPrivacyFeature.shouldSuppressWithOverrides(
                ModernTypingPrivacyFeature.STATE_RECORDING,
                globalGhost = false,
                globalTyping = false,
                globalRecording = false,
                rule = saved,
            ),
        )
    }

    @Test
    fun localSettingsEditIsVisibleOnTheVeryNextEvent() {
        val values = mutableMapOf<String, Any?>("${number}_privacy" to "one")
        val store =
            ModernTargetPrivacyRuleStore(fakePreferences(values)) { raw ->
                ModernTypingPrivacyFeature.PrivacyRule(hideTyping = raw == "one")
            }
        assertEquals(true, store.forPhoneNumber(number)?.hideTyping)
        values["${number}_privacy"] = "two"
        assertEquals(false, store.forPhoneNumber(number)?.hideTyping)
        values.remove("${number}_privacy")
        assertNull(store.forPhoneNumber(number))
    }

    @Test
    fun targetPackagesAndProfilesUseSeparatePreferenceStores() {
        val messenger = mutableMapOf<String, Any?>("${number}_privacy" to "true")
        val business = mutableMapOf<String, Any?>("${number}_privacy" to "false")
        val parse: (String) -> ModernTypingPrivacyFeature.PrivacyRule = { raw ->
            ModernTypingPrivacyFeature.PrivacyRule(hideTyping = raw == "true")
        }
        val a = ModernTargetPrivacyRuleStore(fakePreferences(messenger), parse)
        val b = ModernTargetPrivacyRuleStore(fakePreferences(business), parse)
        assertEquals(true, a.forPhoneNumber(number)?.hideTyping)
        assertEquals(false, b.forPhoneNumber(number)?.hideTyping)
        messenger["${number}_privacy"] = "false"
        assertEquals(false, a.forPhoneNumber(number)?.hideTyping)
        assertEquals(false, b.forPhoneNumber(number)?.hideTyping)
    }

    @Test
    fun invalidIdentifiersAreRejectedWithoutAccessingAnyPreferences() {
        var reads = 0
        val store =
            ModernTargetPrivacyRuleStore(fakePreferences(mutableMapOf()) { reads++ }) {
                throw AssertionError("decoder must not run")
            }
        for (invalid in listOf(null, "", "4912@lid", "-12", "+49123", "12_34", "a12", "7".repeat(33))) {
            assertNull(store.forPhoneNumber(invalid))
        }
        assertEquals(0, reads)
        assertTrue(ModernTargetPrivacyRuleStore.isValidPhoneNumber(number))
    }

    @Test
    fun corruptSavedRuleFailsClosedButMissingRuleInheritsGlobalPreference() {
        val values = mutableMapOf<String, Any?>("${number}_privacy" to "broken")
        val store =
            ModernTargetPrivacyRuleStore(fakePreferences(values)) {
                throw IllegalArgumentException("corrupt rule value")
            }
        val fallback = store.forPhoneNumber(number)
        assertEquals(true, fallback?.hideTyping)
        assertEquals(true, fallback?.hideRecording)
        assertNull(store.forPhoneNumber("491111111111"))
        assertFalse(
            ModernTypingPrivacyFeature.shouldSuppressWithOverrides(
                ModernTypingPrivacyFeature.STATE_TYPING,
                false,
                false,
                false,
                null,
            ),
        )
    }

    @Test
    fun noLocalOverrideRetainsGlobalAndGhostSemantics() {
        val noOverride = ModernTypingPrivacyFeature.PrivacyRule()
        assertTrue(
            ModernTypingPrivacyFeature.shouldSuppressWithOverrides(
                ModernTypingPrivacyFeature.STATE_TYPING, false, true, false, noOverride,
            ),
        )
        assertTrue(
            ModernTypingPrivacyFeature.shouldSuppressWithOverrides(
                ModernTypingPrivacyFeature.STATE_TYPING,
                true,
                false,
                false,
                ModernTypingPrivacyFeature.PrivacyRule(hideTyping = false),
            ),
        )
    }

    @Test
    fun customModeCanEnableHookWithoutAnyGlobalSwitch() {
        assertTrue(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("1"))
        assertTrue(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("2"))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("0"))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled(""))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled(null))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("unsupported"))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("3"))
        assertFalse(ModernTypingPrivacyFeature.isCustomPrivacyEnabled("-1"))
    }

    private fun fakePreferences(
        values: MutableMap<String, Any?>,
        onGetString: () -> Unit = {},
    ): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    onGetString()
                    val key = args?.get(0) as String
                    val value = values[key]
                    if (value == null) args[1] else value as String
                }
                "contains" -> values.containsKey(args?.get(0) as String)
                "getAll" -> values.toMap()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
}
