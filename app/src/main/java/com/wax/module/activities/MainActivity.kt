package com.wax.module.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.navigation.NavigationBarView
import com.waseemsabir.betterypermissionhelper.BatteryPermissionHelper
import com.wax.module.ModuleApplication
import com.wax.module.R
import com.wax.module.activities.base.BaseActivity
import com.wax.module.adapter.MainPagerAdapter
import com.wax.module.databinding.ActivityMainBinding
import com.wax.module.ui.fragments.GeneralFragment
import com.wax.module.ui.fragments.HomeFragment
import com.wax.module.ui.fragments.base.BasePreferenceFragment
import com.wax.module.utils.FilePicker
import java.io.File
import kotlin.math.abs

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private val batteryPermissionHelper = BatteryPermissionHelper.getInstance()
    private var pendingScrollToPreference: String? = null
    private var pendingScrollToFragment = -1
    private var pendingParentKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleApplication.changeLanguage(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = MainPagerAdapter(this)
        // Legacy preference routes remain reachable, but only four primary tabs are visible.
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.setPageTransformer(DepthPageTransformer())

        binding.navView.setOnItemSelectedListener(
            NavigationBarView.OnItemSelectedListener { item ->
                val position =
                    when (item.itemId) {
                        R.id.navigation_home -> 0
                        R.id.navigation_features -> 1
                        R.id.navigation_colors -> 2
                        R.id.navigation_tools -> 3
                        else -> return@OnItemSelectedListener false
                    }
                binding.viewPager.setCurrentItem(position, true)
                true
            },
        )

        binding.viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    val primaryId =
                        when (position) {
                            0 -> R.id.navigation_home
                            2, 8 -> R.id.navigation_colors
                            3 -> R.id.navigation_tools
                            else -> R.id.navigation_features
                        }
                    binding.navView.menu
                        .findItem(primaryId)
                        ?.isChecked = true

                    val scrollKey = pendingScrollToPreference
                    if (pendingScrollToFragment == position && scrollKey != null) {
                        val parentKey = pendingParentKey
                        pendingScrollToPreference = null
                        pendingScrollToFragment = -1
                        pendingParentKey = null
                        binding.viewPager.postDelayed({
                            scrollToPreferenceInCurrentFragment(scrollKey, parentKey)
                        }, 300)
                    }
                }
            },
        )

        if (savedInstanceState == null) binding.viewPager.setCurrentItem(0, false)
        createMainDir()
        FilePicker.registerFilePicker(this)
        handleIncomingIntent(intent)
    }

    /** Opens the existing Home backup/export operations without creating a second backup engine. */
    fun openBackupActions() {
        binding.viewPager.setCurrentItem(0, false)
        binding.viewPager.post {
            val home = supportFragmentManager.findFragmentByTag("f0") as? HomeFragment
            if (home != null) {
                home.openBackupOptions()
            } else {
                binding.viewPager.postDelayed({
                    (supportFragmentManager.findFragmentByTag("f0") as? HomeFragment)?.openBackupOptions()
                }, 200)
            }
        }
    }

    private fun createMainDir() {
        val nomedia = File(ModuleApplication.moduleFolder, ".nomedia")
        if (nomedia.exists()) nomedia.delete()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return

        val legacyPosition = intent.getIntExtra("navigate_to_fragment", -1)
        val fragmentPosition = if (legacyPosition >= 0) LegacyNavigationMap.toPage(legacyPosition) else -1
        val preferenceKey = intent.getStringExtra("scroll_to_preference")
        val parentKey = intent.getStringExtra("parent_preference")

        if (fragmentPosition >= 0 && preferenceKey != null) {
            pendingScrollToPreference = preferenceKey
            pendingScrollToFragment = fragmentPosition
            pendingParentKey = parentKey
            binding.viewPager.setCurrentItem(fragmentPosition, false)
            intent.removeExtra("navigate_to_fragment")
            intent.removeExtra("scroll_to_preference")
            intent.removeExtra("parent_preference")
        } else if (fragmentPosition >= 0) {
            binding.viewPager.setCurrentItem(fragmentPosition, true)
        }
    }

    /**
     * Bridges the new UIX-01 shell into the historical preference owners. Never
     * rewrites a legacy feature key or changes which target owns its settings.
     */
    fun navigateToLegacyFragment(
        position: Int,
        preferenceKey: String? = null,
        parentKey: String? = null,
    ) {
        val page = LegacyNavigationMap.toPage(position)
        pendingScrollToPreference = preferenceKey
        pendingScrollToFragment = if (preferenceKey != null) page else -1
        pendingParentKey = parentKey
        if (binding.viewPager.currentItem == page && preferenceKey != null) {
            binding.viewPager.post { scrollToPreferenceInCurrentFragment(preferenceKey, parentKey) }
        } else {
            binding.viewPager.setCurrentItem(page, false)
        }
    }

    private fun scrollToPreferenceInCurrentFragment(
        preferenceKey: String,
        parentKey: String?,
    ) {
        val currentItem = binding.viewPager.currentItem
        // FragmentStateAdapter tags fragments by their stable item ID, not by their new position.
        // Retained legacy preference pages deliberately have IDs different from their positions.
        val itemId = binding.viewPager.adapter?.getItemId(currentItem) ?: return
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") ?: return

        if (fragment is GeneralFragment || fragment is HomeFragment) {
            if (!parentKey.isNullOrEmpty()) {
                navigateToSubFragmentAndScroll(fragment, parentKey, preferenceKey)
            } else {
                scrollInChildFragment(fragment, preferenceKey)
            }
        } else if (fragment is BasePreferenceFragment) {
            fragment.scrollToPreference(preferenceKey)
        }
    }

    private fun navigateToSubFragmentAndScroll(
        parentFragment: Fragment,
        parentKey: String,
        childPreferenceKey: String,
    ) {
        val subFragment =
            when (parentKey) {
                "general_home" -> GeneralFragment.HomeGeneralPreference()
                "homescreen" -> GeneralFragment.HomeScreenGeneralPreference()
                "conversation" -> GeneralFragment.ConversationGeneralPreference()
                else -> null
            } ?: return

        val parentView = parentFragment.view ?: return
        parentFragment.childFragmentManager
            .beginTransaction()
            .replace(R.id.frag_container, subFragment)
            .commitNow()

        parentView.postDelayed({
            (subFragment as? BasePreferenceFragment)?.scrollToPreference(childPreferenceKey)
        }, 400)
    }

    private fun scrollInChildFragment(
        parentFragment: Fragment,
        preferenceKey: String,
    ) {
        val childFragment = parentFragment.childFragmentManager.findFragmentById(R.id.frag_container)
        (childFragment as? BasePreferenceFragment)?.scrollToPreference(preferenceKey)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.header_menu, menu)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            menu.findItem(R.id.batteryoptimization).isVisible = false
        }
        return true
    }

    @SuppressLint("BatteryLife")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> {
                val options =
                    ActivityOptionsCompat.makeCustomAnimation(
                        this,
                        R.anim.slide_in_right,
                        R.anim.slide_out_left,
                    )
                startActivity(Intent(this, SearchActivity::class.java), options.toBundle())
                return true
            }

            R.id.menu_settings -> {
                startActivity(Intent(this, ManagerSettingsActivity::class.java))
                return true
            }

            R.id.menu_about -> {
                val options =
                    ActivityOptionsCompat.makeCustomAnimation(
                        this,
                        R.anim.slide_in_right,
                        R.anim.slide_out_left,
                    )
                startActivity(Intent(this, AboutActivity::class.java), options.toBundle())
                return true
            }

            R.id.batteryoptimization -> {
                if (batteryPermissionHelper.isBatterySaverPermissionAvailable(this, true)) {
                    batteryPermissionHelper.getPermission(this, true, true)
                } else {
                    val batteryIntent =
                        Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = "package:$packageName".toUri()
                        }
                    startActivity(batteryIntent)
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private class DepthPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(
            page: android.view.View,
            position: Float,
        ) {
            val pageWidth = page.width
            when {
                position < -1 -> {
                    page.alpha = 0f
                }

                position <= 0 -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.translationZ = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }

                position <= 1 -> {
                    page.alpha = 1 - position
                    page.translationX = pageWidth * -position
                    page.translationZ = -1f
                    val scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - abs(position))
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                }

                else -> {
                    page.alpha = 0f
                }
            }
        }

        companion object {
            private const val MIN_SCALE = 0.85f
        }
    }
}
