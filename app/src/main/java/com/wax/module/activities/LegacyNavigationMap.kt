package com.wax.module.activities

/**
 * Stable search/deep-link ABI: old positions 0..5 must continue addressing
 * their original preference owners after the four-tab shell migration.
 */
internal object LegacyNavigationMap {
    /** Four primary tabs own the back destination of each historical editor. */
    fun primaryForPage(page: Int): Int? =
        when (page) {
            in 4..7 -> 1

            // Features, including legacy General, Privacy, Media and Recordings
            8 -> 2

            // Customization preview
            else -> null
        }

    fun toPage(oldPosition: Int): Int =
        when (oldPosition) {
            0 -> 0

            // Home
            1 -> 4

            // General
            2 -> 5

            // Privacy
            3 -> 6

            // Media
            4 -> 8

            // Original customization preference owner, not preview
            5 -> 7

            // Recordings
            else -> 1 // Feature index is a safe non-operational fallback
        }
}
