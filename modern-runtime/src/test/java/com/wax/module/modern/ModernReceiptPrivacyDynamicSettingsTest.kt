package com.wax.module.modern

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook must observe live settings after installation. No device/chat
 * contents are involved in these pure control-path regression fixtures.
 */
class ModernReceiptPrivacyDynamicSettingsTest {
    private val flags = mutableMapOf(
        ModernReceiptPrivacyFeature.PREF_HIDE_READ to true,
        ModernReceiptPrivacyFeature.PREF_AFTER_REPLY to false,
    )
    private val prefs: SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    val parameters = requireNotNull(args)
                    flags[parameters[0] as String] ?: (parameters[1] as Boolean)
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences

    @Test
    fun turningReadPrivacyOffRestoresNativeBehaviorInAlreadyInstalledHook() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 100L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_HIDE_READ] = false
        assertFalse(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 101L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_HIDE_READ] = true
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 102L,
        ))
    }

    @Test
    fun turningPrivacyOffInvalidatesAPendingReplyRelease() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = true
        assertTrue(ModernReceiptPrivacyFeature.onSendCompleted(
            "chat", ModernReceiptPrivacyFeature.SendResult.SUCCEEDED, 100L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_HIDE_READ] = false
        assertFalse(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 101L,
        ))
        assertFalse(ModernReceiptPrivacyFeature.isArmed("chat", 102L))
        flags[ModernReceiptPrivacyFeature.PREF_HIDE_READ] = true
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 103L,
        ))
    }

    @Test
    fun turningAfterReplyOffInvalidatesOldReleaseWithoutDisablingReadPrivacy() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = true
        assertTrue(ModernReceiptPrivacyFeature.onSendCompleted(
            "chat", ModernReceiptPrivacyFeature.SendResult.SUCCEEDED, 100L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = false
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 101L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = true
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 102L,
        ))
    }

    @Test
    fun armedReplyNeverReleasesAnUnrelatedConversationOrAnUnknownOne() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = true
        ModernReceiptPrivacyFeature.onSendCompleted(
            "chat-a", ModernReceiptPrivacyFeature.SendResult.SUCCEEDED, 1_000L,
        )
        val request = ModernReceiptPrivacyFeature.readRequest(prefs)
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(request, null, 1_001L))
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(request, "chat-b", 1_002L))
        assertFalse(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(request, "chat-a", 1_003L))
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(request, "chat-a", 1_004L))
    }

    @Test
    fun corruptedClockOrAfterReplyWithoutReadPrivacyCannotRelease() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        flags[ModernReceiptPrivacyFeature.PREF_AFTER_REPLY] = true
        ModernReceiptPrivacyFeature.onSendCompleted(
            "chat", ModernReceiptPrivacyFeature.SendResult.SUCCEEDED, 1_000L,
        )
        assertTrue(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", -1L,
        ))
        flags[ModernReceiptPrivacyFeature.PREF_HIDE_READ] = false
        assertFalse(ModernReceiptPrivacyFeature.shouldWithholdReadReceipt(
            ModernReceiptPrivacyFeature.readRequest(prefs), "chat", 1_002L,
        ))
        assertFalse(ModernReceiptPrivacyFeature.isArmed("chat", 1_003L))
    }
}
