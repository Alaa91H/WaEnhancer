package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Object.toString() is present on every Java class, but is not a verified
 * WhatsApp raw-JID accessor. Prove unique-or-safe behavior without target APK.
 */
class ModernJidAccessorResolutionTest {
    private class RawAndDisplay {
        fun rawAddress(): String = "491234567890.1:2@s.whatsapp.net"

        override fun toString(): String = "DISPLAY_ONLY_NOT_A_JID"
    }

    private class InheritedRaw {
        fun rawAddress(): String = "491234567890@s.whatsapp.net"
    }

    private class OnlyDisplay {
        override fun toString(): String = "491234567890@s.whatsapp.net"
    }

    private class TwoRawCandidates {
        fun first(): String = "491234567890@s.whatsapp.net"

        fun second(): String = "another-string"
    }

    @Test
    fun realAccessorAndOverriddenToStringDoNotCauseFalseAmbiguity() {
        val resolution = ModernJidAccess.resolve(RawAndDisplay::class.java)
        assertEquals(ModernJidAccess.Outcome.AVAILABLE, resolution.outcome)
        val access = requireNotNull(resolution.access)
        assertTrue(resolution.available)
        assertEquals("491234567890@s.whatsapp.net", access.rawString(RawAndDisplay()))
        assertEquals("491234567890", access.phoneNumber(RawAndDisplay()))
    }

    @Test
    fun inheritedObjectToStringCannotObscureUniqueRawAccessor() {
        val resolution = ModernJidAccess.resolve(InheritedRaw::class.java)
        assertEquals(ModernJidAccess.Outcome.AVAILABLE, resolution.outcome)
        assertEquals(
            "491234567890@s.whatsapp.net",
            resolution.access?.rawString(InheritedRaw()),
        )
    }

    @Test
    fun toStringAloneIsNeverTreatedAsPhoneIdentityProof() {
        val resolution = ModernJidAccess.resolve(OnlyDisplay::class.java)
        assertFalse(resolution.available)
        assertNull(resolution.access)
        assertEquals(ModernJidAccess.Outcome.RAW_STRING_METHOD_MISSING, resolution.outcome)
        assertEquals(
            ModernJidAccess.Outcome.RAW_STRING_METHOD_MISSING,
            ModernJidAccess.resolve(Any::class.java).outcome,
        )
    }

    @Test
    fun twoRealStringAccessorsStillFailClosed() {
        val resolution = ModernJidAccess.resolve(TwoRawCandidates::class.java)
        assertNull(resolution.access)
        assertEquals(ModernJidAccess.Outcome.RAW_STRING_METHOD_AMBIGUOUS, resolution.outcome)
    }
}
