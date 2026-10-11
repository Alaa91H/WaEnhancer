package com.wax.module.ui.features

import com.wax.module.settings.SettingKeyRegistry

/**
 * Unknown API102 compatibility/account-risk evidence must never be treated as LOW.
 *
 * A saved Manager preference is NOT a verified compatible runtime hook. An
 * explicit enable requires consent; turning a setting off remains immediate.
 * Signed risk and compatibility decisions belong to A03/M12, not this UI.
 */
internal object FeatureTogglePolicy {
    enum class Decision {
        NOT_EDITABLE,
        NO_CHANGE,
        DISABLE_IMMEDIATELY,
        EXPLICIT_ENABLE_CONFIRMATION,
    }

    fun decision(
        kind: SettingKeyRegistry.Kind?,
        current: Boolean,
        desired: Boolean,
    ): Decision =
        when {
            kind != SettingKeyRegistry.Kind.BOOLEAN -> Decision.NOT_EDITABLE
            current == desired -> Decision.NO_CHANGE
            !desired -> Decision.DISABLE_IMMEDIATELY
            else -> Decision.EXPLICIT_ENABLE_CONFIRMATION
        }
}
