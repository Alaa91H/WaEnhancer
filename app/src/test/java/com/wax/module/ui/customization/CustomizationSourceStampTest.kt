package com.wax.module.ui.customization

import com.wax.module.platform.TargetApp
import com.wax.module.settings.SettingsKeys
import com.wax.module.settings.SettingsScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CustomizationSourceStampTest {
    private val whatsapp = SettingsScope.Target(TargetApp.WHATSAPP)
    private val business = SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)

    @Test fun detectsTargetOverrideChangesEvenWhenEffectiveValueIsUnchanged() {
        val global = SettingsKeys.physicalKey(SettingsScope.Global, "channels")
        val target = SettingsKeys.physicalKey(whatsapp, "channels")
        val before = mapOf(global to false)
        val after = mapOf(global to false, target to false)
        assertNotEquals(
            CustomizationSourceStamp.fromValues(whatsapp, before),
            CustomizationSourceStamp.fromValues(whatsapp, after),
        )
    }

    @Test fun unrelatedSettingsAndOppositeTargetDoNotInvalidateThePreview() {
        val before = mapOf("channels" to false)
        val later =
            before +
                mapOf(
                    "unrelated_key" to "unrelated",
                    SettingsKeys.physicalKey(business, "channels") to true,
                )
        assertEquals(
            CustomizationSourceStamp.fromValues(whatsapp, before),
            CustomizationSourceStamp.fromValues(whatsapp, later),
        )
    }

    @Test fun globalParentChangeInvalidatesOnlyInheritedOrSameSelectedSource() {
        val target = SettingsKeys.physicalKey(whatsapp, "channels")
        val before = mapOf("channels" to false, target to true)
        val later = mapOf("channels" to true, target to true)
        assertNotEquals(
            CustomizationSourceStamp.fromValues(whatsapp, before),
            CustomizationSourceStamp.fromValues(whatsapp, later),
        )
    }

    @Test fun bubbleColorChangeInvalidatesScopedSnapshot() {
        val before = mapOf("bubble_left" to 0xFF008069.toInt(), "bubble_right" to 0xFF008069.toInt())
        val after = before + ("bubble_right" to 0xFF1661A7.toInt())
        assertNotEquals(
            CustomizationSourceStamp.fromValues(whatsapp, before),
            CustomizationSourceStamp.fromValues(whatsapp, after),
        )
    }

    @Test fun setElementOrderDoesNotCreatePhantomConflict() {
        val first = mapOf("hidetabs" to linkedSetOf("300", "700", "400"))
        val second = mapOf("hidetabs" to linkedSetOf("400", "300", "700"))
        assertEquals(
            CustomizationSourceStamp.fromValues(whatsapp, first),
            CustomizationSourceStamp.fromValues(whatsapp, second),
        )
    }
}
