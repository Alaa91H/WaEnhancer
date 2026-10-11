package com.wax.module.activities

/**
 * Stable search/deep-link ABI: old positions 0..5 must continue addressing
 * their original preference owners after the four-tab shell migration.
 */
internal object LegacyNavigationMap {
    fun toPage(oldPosition: Int): Int = when (oldPosition) {
        0 -> 0 // Home
        1 -> 4 // General
        2 -> 5 // Privacy
        3 -> 6 // Media
        4 -> 8 // Original customization preference owner, not preview
        5 -> 7 // Recordings
        else -> 1 // Feature index is a safe non-operational fallback
    }
}
