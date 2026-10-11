package com.wax.module.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import com.wax.module.ui.targets.TargetSettingsActivity
import com.wax.module.ui.theme.WaXTheme
import com.wax.module.utils.FeatureCatalog

/** Reuses the canonical catalog and original preference destinations. */
class FeatureHubFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WaXTheme {
                    Browser(
                        onOpen = { feature ->
                            (activity as? MainActivity)?.navigateToLegacyFragment(
                                feature.fragmentType.position, feature.key, feature.parentKey,
                            )
                        },
                        onTargets = {
                            startActivity(Intent(requireContext(), TargetSettingsActivity::class.java))
                        },
                    )
                }
            }
        }

    @Composable
    private fun Browser(onOpen: (SearchableFeature) -> Unit, onTargets: () -> Unit) {
        val context = requireContext()
        val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
        val entries = remember(context) { FeatureCatalog.getAllFeatures(context) }
        val favorites = remember(entries) {
            mutableStateListOf<String>().apply {
                addAll(entries.map { it.key }.filter { prefs.getBoolean("uix_favorite_" + it, false) })
            }
        }
        var query by rememberSaveable { mutableStateOf("") }
        var selected by rememberSaveable { mutableStateOf("all") }
        val groups = listOf("all", "favorites", "privacy", "general", "media", "customization")
        val filtered = entries.filter { feature ->
            val groupMatch = when (selected) {
                "favorites" -> feature.key in favorites
                "privacy" -> feature.category == SearchableFeature.Category.PRIVACY
                "general" -> feature.category.name.startsWith("GENERAL")
                "media" -> feature.category == SearchableFeature.Category.MEDIA ||
                    feature.category == SearchableFeature.Category.RECORDINGS
                "customization" -> feature.category == SearchableFeature.Category.CUSTOMIZATION
                else -> true
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
                        OutlinedButton(onClick = onTargets) {
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
                                    Text(when (group) {
                                        "all" -> stringResource(R.string.uix_all)
                                        "favorites" -> stringResource(R.string.uix_favorites)
                                        "privacy" -> stringResource(R.string.privacy)
                                        "general" -> stringResource(R.string.general)
                                        "media" -> stringResource(R.string.media)
                                        else -> stringResource(R.string.perso)
                                    })
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
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.weight(1f).clickable { onOpen(feature) }.padding(16.dp),
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
                            }
                            IconButton(onClick = {
                                val enabled = feature.key !in favorites
                                if (enabled) favorites.add(feature.key) else favorites.remove(feature.key)
                                prefs.edit().putBoolean("uix_favorite_" + feature.key, enabled).apply()
                            }) {
                                Text(
                                    if (feature.key in favorites) "★" else "☆",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
            }
        }
    }
}
