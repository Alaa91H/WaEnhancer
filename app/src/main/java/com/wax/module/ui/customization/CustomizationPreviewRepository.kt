package com.wax.module.ui.customization

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.wax.module.settings.EffectiveSettingsResolver
import com.wax.module.settings.SettingsKeys
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.SharedPreferencesSettingsStore

/**
 * One target-aware typed source of truth, shared with TargetSettingsActivity.
 * All staged changes commit in a single SharedPreferences transaction.
 * The "expected" snapshot is verified first to avoid overwriting newer edits
 * made by the original preference screen while the preview is open.
 */
class CustomizationPreviewRepository(
    context: Context,
) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Only five preview preferences and their inherited Global parents are fingerprinted. */
    fun fingerprint(scope: SettingsScope): String = CustomizationSourceStamp.fromValues(scope, prefs.all)

    fun read(scope: SettingsScope): CustomizationPreviewState {
        val resolver = EffectiveSettingsResolver(SharedPreferencesSettingsStore(prefs))
        return CustomizationPreviewState(
            colorsEnabled = resolver.effectiveBoolean("changecolor", scope),
            accentColor = resolver.effectiveInt("primary_color", scope),
            hideChannels = resolver.effectiveBoolean("channels", scope),
            hiddenTabs = resolver.effectiveStringSet("hidetabs", scope),
            floatingBottomBar = resolver.effectiveBoolean("floating_bottom_bar", scope),
        )
    }

    /** Returns false on concurrent modification or a failed disk write. Never claims success on failure. */
    fun save(
        scope: SettingsScope,
        expected: CustomizationPreviewState,
        draft: CustomizationPreviewState,
        expectedFingerprint: String,
    ): Boolean {
        // A matching *effective value* is insufficient: another editor may have
        // modified a target override whose current value matches Global.
        // Optimistic detection only: SharedPreferences has no multi-writer CAS API.
        if (fingerprint(scope) != expectedFingerprint || read(scope) != expected) return false
        if (expected == draft) return true
        val edit = prefs.edit()

        fun key(name: String) = SettingsKeys.physicalKey(scope, name)
        if (expected.colorsEnabled != draft.colorsEnabled) edit.putBoolean(key("changecolor"), draft.colorsEnabled)
        if (expected.accentColor != draft.accentColor) edit.putInt(key("primary_color"), draft.accentColor)
        if (expected.hideChannels != draft.hideChannels) edit.putBoolean(key("channels"), draft.hideChannels)
        if (expected.hiddenTabs != draft.hiddenTabs) edit.putStringSet(key("hidetabs"), draft.hiddenTabs.toSet())
        if (expected.floatingBottomBar != draft.floatingBottomBar) {
            edit.putBoolean(key("floating_bottom_bar"), draft.floatingBottomBar)
        }
        return edit.commit()
    }
}
