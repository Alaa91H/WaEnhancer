package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ambiguous JID parameters cannot select an arbitrary contact privacy rule. */
class ModernTypingPrivacyJidSelectionTest {
    @Test
    fun uniqueJidIsRequiredForPerContactAttribution() {
        val recipient = "491234@s.whatsapp.net"
        assertEquals(
            recipient,
            ModernTypingPrivacyFeature.uniqueJidArgument(
                listOf(0, recipient, 1),
                String::class.java,
            ),
        )
        assertNull(
            ModernTypingPrivacyFeature.uniqueJidArgument(
                listOf("sender", "recipient", 0),
                String::class.java,
            ),
        )
        assertNull(
            ModernTypingPrivacyFeature.uniqueJidArgument(
                listOf(1, 2, null),
                String::class.java,
            ),
        )
        assertNull(
            ModernTypingPrivacyFeature.uniqueJidArgument(
                listOf(recipient, recipient),
                String::class.java,
            ),
        )
    }
}
