package com.wax.module.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wax.module.ui.fragments.*

/**
 * Four visible Manager destinations with retained historical preference owners.
 * Legacy stable IDs 0..5 are preserved across fragment/process state restoration;
 * the new destinations get IDs outside that range.
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 8

    override fun getItemId(position: Int): Long = when (position) {
        0 -> 0L // Home (legacy ID 0)
        1 -> 100L // New feature browser
        2 -> 4L // Customization (legacy ID 4)
        3 -> 101L // New tools hub
        4 -> 1L // General (legacy ID 1)
        5 -> 2L // Privacy (legacy ID 2)
        6 -> 3L // Media (legacy ID 3)
        7 -> 5L // Recordings (legacy ID 5)
        else -> throw IllegalArgumentException("Unknown Manager page")
    }

    override fun containsItem(itemId: Long): Boolean =
        itemId in setOf(0L, 1L, 2L, 3L, 4L, 5L, 100L, 101L)

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> HomeFragment()
        1 -> FeatureHubFragment()
        2 -> CustomizationFragment()
        3 -> ToolsHubFragment()
        4 -> GeneralFragment()
        5 -> PrivacyFragment()
        6 -> MediaFragment()
        7 -> RecordingsFragment()
        else -> throw IllegalArgumentException("Unknown Manager page")
    }
}
