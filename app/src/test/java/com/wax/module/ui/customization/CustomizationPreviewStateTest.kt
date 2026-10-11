package com.wax.module.ui.customization

import org.junit.Assert.*
import org.junit.Test

class CustomizationPreviewStateTest {
    @Test fun channelsAndContactStatusAreIndependent() {
        val state = CustomizationPreviewState(hideChannels = true)
        assertFalse(state.showChannelsSection)
        assertTrue(state.showStatusSection)
        assertTrue(state.showUpdatesTab)
    }

    @Test fun entireUpdatesTabDoesNotModifyChannelPreference() {
        val state = CustomizationPreviewState(hideChannels = false).withTabHidden("300", true)
        assertFalse(state.showUpdatesTab)
        assertFalse(state.showStatusSection)
        assertFalse(state.showChannelsSection)
        assertFalse(state.hideChannels)
    }

    @Test fun togglesPreserveLegacyUnknownTabIDs() {
        val original = CustomizationPreviewState(hiddenTabs = setOf("700", "400"))
        val updated = original.withTabHidden("600", true).withTabHidden("400", false)
        assertEquals(setOf("700", "600"), updated.hiddenTabs)
        assertTrue(updated.showCalls)
        assertFalse(updated.showCommunities)
    }

    @Test fun allSixteenSupportedVisibilityCombinationsStayIndependent() {
        // The fourth currently supported flag is the *entire Updates tab*.
        // Hiding only contact Status is a separate, not-yet-implemented hook.
        for (mask in 0 until 16) {
            val hideChannels = mask and 1 != 0
            val hideCommunities = mask and 2 != 0
            val hideCalls = mask and 4 != 0
            val hideUpdates = mask and 8 != 0
            val state = CustomizationPreviewState(hideChannels = hideChannels)
                .withTabHidden("600", hideCommunities)
                .withTabHidden("400", hideCalls)
                .withTabHidden("300", hideUpdates)
            assertEquals("channels value $mask", hideChannels, state.hideChannels)
            assertEquals("communities $mask", !hideCommunities, state.showCommunities)
            assertEquals("calls $mask", !hideCalls, state.showCalls)
            assertEquals("updates $mask", !hideUpdates, state.showUpdatesTab)
            assertEquals("status $mask", !hideUpdates, state.showStatusSection)
            assertEquals("channels visible $mask", !hideUpdates && !hideChannels, state.showChannelsSection)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuseToInventUnsupportedTabValue() {
        CustomizationPreviewState().withTabHidden("999", true)
    }
}
