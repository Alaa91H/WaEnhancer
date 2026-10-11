package com.wax.module.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wax.module.ui.fragments.*

/** Four visible destinations + invisible, retained legacy preference owners. */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 8

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
