package com.wax.module.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.wax.module.R
import com.wax.module.activities.MainActivity
import com.wax.module.model.SearchableFeature
import com.wax.module.platform.TargetApp
import com.wax.module.settings.EffectiveSettingsResolver
import com.wax.module.settings.SettingKeyRegistry
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.SharedPreferencesSettingsStore
import com.wax.module.ui.targets.TargetSettingsActivity
import com.wax.module.ui.theme.WaXTheme
import com.wax.module.utils.FeatureCatalog

/**
 * UIX-01.2 feature browser. One canonical registry and target-aware settings store;
 * saved configuration is deliberately NOT mislabelled as a verified working hook.
 */
class FeatureHubFragment : Fragment() {
    private val screenRevision = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        screenRevision.intValue++
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WaXTheme {
                    Browser(
                        externalRevision = screenRevision.intValue,
                        onOpen = { feature, scopeChoice ->
                            if (scopeChoice == 0) {
                                (activity as? MainActivity)?.navigateToLegacyFragment(
                                    feature.fragmentType.position,
                                    feature.key,
                                    feature.parentKey,
                                )
                            } else {
                                val target = if (scopeChoice == 1) TargetApp.WHATSAPP else TargetApp.WHATSAPP_BUSINESS
                                startActivity(
                                    Intent(requireContext(), TargetSettingsActivity::class.java)
                                        .putExtra(TargetSettingsActivity.EXTRA_TARGET_CODE, target.code)
                                        .putExtra(TargetSettingsActivity.EXTRA_SETTING_QUERY, feature.key),
                                )
                            }
                        },
                        onTargetSettings = { scopeChoice ->
                            val code =
                                when (scopeChoice) {
                                    1 -> TargetApp.WHATSAPP.code
                                    2 -> TargetApp.WHATSAPP_BUSINESS.code
                                    else -> null
                                }
                            startActivity(
                                Intent(requireContext(), TargetSettingsActivity::class.java)
                                    .putExtra(TargetSettingsActivity.EXTRA_TARGET_CODE, code),
                            )
                        },
                    )
                }
            }
        }

    @Composable
    private fun Browser(
        externalRevision: Int,
        onOpen: (SearchableFeature, Int) -> Unit,
        onTargetSettings: (Int) -> Unit,
    ) {
        val context = requireContext()
        val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
        val store = remember(context) { SharedPreferencesSettingsStore(prefs) }
        val entries = remember(context) { FeatureCatalog.getAllFeatures(context) }
        val declared = remember { SettingKeyRegistry.entries.associateBy { it.key } }
        val favorites =
            remember(entries) {
                mutableStateListOf<String>().apply {
                    addAll(entries.map { it.key }.filter { prefs.getBoolean("uix_favorite_" + it, false) })
                }
            }
        var query by rememberSaveable { mutableStateOf("") }
        var selected by rememberSaveable { mutableStateOf("all") }
        var scopeChoice by rememberSaveable { mutableIntStateOf(0) }
        var localRevision by remember { mutableIntStateOf(0) }

        // UI reloads cached settings after a change in this screen or any returning editor.
        val resolver =
            remember(scopeChoice, localRevision, externalRevision) {
                store.reload()
                EffectiveSettingsResolver(store)
            }
        val scope: SettingsScope =
            when (scopeChoice) {
                1 -> SettingsScope.Target(TargetApp.WHATSAPP)
                2 -> SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)
                else -> SettingsScope.Global
            }
        val groups = listOf("all", "favorites", "privacy", "general", "media", "customization")
        val filtered =
            entries.filter { feature ->
                val groupMatch =
                    when (selected) {
                        "favorites" -> {
                            feature.key in favorites
                        }

                        "privacy" -> {
                            feature.category == SearchableFeature.Category.PRIVACY
                        }

                        "general" -> {
                            feature.category.name.startsWith("GENERAL")
                        }

                        "media" -> {
                            feature.category == SearchableFeature.Category.MEDIA ||
                                feature.category == SearchableFeature.Category.RECORDINGS
                        }

                        "customization" -> {
                            feature.category == SearchableFeature.Category.CUSTOMIZATION
                        }

                        else -> {
                            true
                        }
                    }
                groupMatch && (query.isBlank() || feature.matches(query))
            }

        Scaffold { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.uix_features), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.uix_feature_honest_status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(R.string.uix_global, R.string.uix_whatsapp, R.string.uix_business).forEachIndexed { index, title ->
                                FilterChip(
                                    selected = scopeChoice == index,
                                    onClick = { scopeChoice = index },
                                    label = { Text(stringResource(title), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                        OutlinedButton(onClick = { onTargetSettings(scopeChoice) }) {
                            Text(stringResource(R.string.open_per_target_settings))
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.search_features_title)) },
                        )
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(groups) { group ->
                            FilterChip(
                                selected = selected == group,
                                onClick = { selected = group },
                                label = {
                                    Text(
                                        when (group) {
                                            "all" -> stringResource(R.string.uix_all)
                                            "favorites" -> stringResource(R.string.uix_favorites)
                                            "privacy" -> stringResource(R.string.privacy)
                                            "general" -> stringResource(R.string.general)
                                            "media" -> stringResource(R.string.media)
                                            else -> stringResource(R.string.perso)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.uix_found_count, filtered.size),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (filtered.isEmpty()) {
                    item { Text(stringResource(R.string.uix_no_results), modifier = Modifier.padding(20.dp)) }
                }
                items(filtered, key = { it.key }) { feature ->
                    val setting = declared[feature.key]
                    val isBoolean = setting?.kind == SettingKeyRegistry.Kind.BOOLEAN
                    val chosen = if (isBoolean) resolver.effectiveBoolean(feature.key, scope) else false
                    val overridden =
                        isBoolean && scope is SettingsScope.Target &&
                            store.readBoolean(scope, feature.key) != null
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.weight(1f).clickable { onOpen(feature, scopeChoice) }.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(feature.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        feature.summary ?: feature.category.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (isBoolean) {
                                            stringResource(R.string.uix_saved_unverified)
                                        } else {
                                            stringResource(R.string.uix_open_editor)
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Column(modifier = Modifier.padding(end = 6.dp)) {
                                    IconButton(onClick = {
                                        val enable = feature.key !in favorites
                                        if (enable) favorites.add(feature.key) else favorites.remove(feature.key)
                                        prefs.edit().putBoolean("uix_favorite_" + feature.key, enable).apply()
                                    }) {
                                        Text(
                                            if (feature.key in favorites) "★" else "☆",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                    }
                                    if (isBoolean) {
                                        Switch(
                                            checked = chosen,
                                            onCheckedChange = { enabled ->
                                                store.writeBoolean(scope, feature.key, enabled)
                                                localRevision++
                                            },
                                            colors =
                                                SwitchDefaults.colors(
                                                    checkedTrackColor = Color(0xFF208B4D),
                                                    checkedThumbColor = Color.White,
                                                    uncheckedTrackColor = Color.Gray,
                                                ),
                                        )
                                    }
                                }
                            }
                            if (overridden) {
                                TextButton(
                                    onClick = {
                                        store.writeBoolean(scope, feature.key, null)
                                        localRevision++
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(stringResource(R.string.uix_use_global))
                                }
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
            }
        }
    }
}
