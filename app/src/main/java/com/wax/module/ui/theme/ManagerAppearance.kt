package com.wax.module.ui.theme

/**
 * WA X Manager appearance is independent from WhatsApp's theme settings.
 * Values 0/1/2 retain the long-standing preference wire format.
 */
enum class ManagerAppearance(val dark: Boolean, val amoled: Boolean) {
    LIGHT(false, false),
    DARK(true, false),
    AMOLED(true, true);

    companion object {
        fun fromStored(stored: String?, systemDark: Boolean): ManagerAppearance = when (stored) {
            "1" -> DARK
            "2" -> LIGHT
            "3" -> AMOLED
            else -> if (systemDark) DARK else LIGHT
        }
    }
}
