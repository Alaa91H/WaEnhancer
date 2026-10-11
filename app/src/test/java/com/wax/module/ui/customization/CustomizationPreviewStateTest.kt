package com.wax.module.ui.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
            val state =
                CustomizationPreviewState(hideChannels = hideChannels)
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

    @Test fun pendingPreviewSurvivesStateRestorationIncludingUnknownLegacyTabs() {
        val original =
            CustomizationPreviewState(
                colorsEnabled = true,
                accentColor = 0xFF123456.toInt(),
                hideChannels = true,
                hiddenTabs = setOf("700", "300", "600"),
                floatingBottomBar = true,
                bubbleColorsEnabled = true,
                leftBubbleColor = 0xFF008069.toInt(),
                rightBubbleColor = 0xFF1661A7.toInt(),
            )
        assertEquals(original, CustomizationPreviewState.fromSavedFields(original.savedFields()))
        assertTrue(CustomizationPreviewState.fromSavedFields(original.savedFields()).hiddenTabs.contains("700"))
    }

    @Test fun oldFiveFieldSavedPreviewRestoresSafely() {
        val legacy = listOf<Any>(true, 0xFF008069.toInt(), false, "700,300", false)
        val restored = CustomizationPreviewState.fromSavedFields(legacy)
        assertTrue(restored.colorsEnabled)
        assertEquals(setOf("700", "300"), restored.hiddenTabs)
        assertFalse(restored.bubbleColorsEnabled)
        assertEquals(0, restored.leftBubbleColor)
        assertEquals(0, restored.rightBubbleColor)
    }

    @Test fun cleanPreviewAndPendingPreviewKeepDistinctSnapshots() {
        val baseline = CustomizationPreviewState(hiddenTabs = setOf("400"))
        val draft = baseline.copy(colorsEnabled = true)
        assertNotEquals(
            CustomizationPreviewState.fromSavedFields(baseline.savedFields()),
            CustomizationPreviewState.fromSavedFields(draft.savedFields()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refuseToInventUnsupportedTabValue() {
        CustomizationPreviewState().withTabHidden("999", true)
    }
}
