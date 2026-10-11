package com.wax.module.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** A Manager-only display preference, never forwarded to injected WhatsApp hooks. */
internal object ManagerMotionPreference {
    const val KEY = "wax.manager.reduce_motion"

    /** Physical left/off and right/on coordinates for the static, non-animated thumb. */
    fun physicalThumbX(
        enabled: Boolean,
        trackWidth: Float,
        insetAndRadius: Float,
    ): Float = if (enabled) trackWidth - insetAndRadius else insetAndRadius
}

/** Every Manager Compose surface receives this value from WaXTheme. */
internal val LocalManagerReducedMotion = staticCompositionLocalOf { false }
