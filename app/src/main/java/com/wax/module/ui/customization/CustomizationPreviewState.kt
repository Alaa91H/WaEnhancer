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
    /** Every value uses a Bundle-saveable primitive; unknown legacy tab IDs survive. */
    fun savedFields(): List<Any> =
        listOf(
            colorsEnabled,
            accentColor,
            hideChannels,
            hiddenTabs.sorted().joinToString(","),
            floatingBottomBar,
        )

    companion object {
        fun fromSavedFields(values: List<Any>): CustomizationPreviewState {
            require(values.size == 5) { "Unsupported preview state" }
            return CustomizationPreviewState(
                colorsEnabled = values[0] as Boolean,
                accentColor = values[1] as Int,
                hideChannels = values[2] as Boolean,
                hiddenTabs = (values[3] as String).split(',').filter(String::isNotBlank).toSet(),
                floatingBottomBar = values[4] as Boolean,
            )
        }
    }

    val showCommunities: Boolean get() = "600" !in hiddenTabs
    val showCalls: Boolean get() = "400" !in hiddenTabs
    val showUpdatesTab: Boolean get() = "300" !in hiddenTabs
    val showChannelsSection: Boolean get() = showUpdatesTab && !hideChannels
    val showStatusSection: Boolean get() = showUpdatesTab

    fun withTabHidden(
        tabId: String,
        hide: Boolean,
    ): CustomizationPreviewState {
        require(tabId in setOf("300", "400", "600")) { "Unsupported navigation tab ID" }
        return copy(hiddenTabs = if (hide) hiddenTabs + tabId else hiddenTabs - tabId)
    }
}
