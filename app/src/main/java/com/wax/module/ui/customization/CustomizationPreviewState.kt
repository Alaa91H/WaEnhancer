package com.wax.module.ui.customization

/**
 * All fields are derived from existing registered WA X preference keys.
 * A preview does not mean that API 102 has a verified corresponding runtime hook.
 *
 * Hide Status *section* is intentionally NOT represented as a persisted preference:
 * hidetabs=300 means hide the entire Updates tab, not just contacts' Status.
 */
data class CustomizationPreviewState(
    val colorsEnabled: Boolean = false,
    val accentColor: Int = 0,
    val hideChannels: Boolean = false,
    val hiddenTabs: Set<String> = emptySet(),
    val floatingBottomBar: Boolean = false,
) {
    val showCommunities: Boolean get() = "600" !in hiddenTabs
    val showCalls: Boolean get() = "400" !in hiddenTabs
    val showUpdatesTab: Boolean get() = "300" !in hiddenTabs
    val showChannelsSection: Boolean get() = showUpdatesTab && !hideChannels
    val showStatusSection: Boolean get() = showUpdatesTab

    fun withTabHidden(tabId: String, hide: Boolean): CustomizationPreviewState {
        require(tabId in setOf("300", "400", "600")) { "Unsupported navigation tab ID" }
        return copy(hiddenTabs = if (hide) hiddenTabs + tabId else hiddenTabs - tabId)
    }
}
