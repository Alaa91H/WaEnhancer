package com.wax.module.ui.customization

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.wax.module.R
import com.wax.module.activities.MainActivity
import com.wax.module.platform.TargetApp
import com.wax.module.settings.SettingsScope
import com.wax.module.ui.components.WaXFeatureSwitch
import com.wax.module.ui.targets.TargetSettingsActivity
import com.wax.module.ui.theme.WaXTheme

/**
 * UIX-01.3: a real target-aware customization preview. The illustrative mini UI is
 * explicitly separate from actual WhatsApp rendering and verified runtime behavior.
 * Old/custom CSS and advanced settings are still opened in their original editor.
 */
class CustomizationDashboardFragment : Fragment() {
    private val refresh = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            id = R.id.manager_customization_compose_root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val repo = CustomizationPreviewRepository(context)
            setContent {
                WaXTheme {
                    CustomizationDashboard(
                        repository = repo,
                        externalRevision = refresh.intValue,
                        openAdvanced = { selected ->
                            if (selected == 0) {
                                (activity as? MainActivity)?.navigateToLegacyFragment(4)
                            } else {
                                val target = if (selected == 1) TargetApp.WHATSAPP else TargetApp.WHATSAPP_BUSINESS
                                startActivity(
                                    Intent(requireContext(), TargetSettingsActivity::class.java)
                                        .putExtra(TargetSettingsActivity.EXTRA_TARGET_CODE, target.code),
                                )
                            }
                        },
                    )
                }
            }
        }
}

private val previewSaver =
    listSaver<CustomizationPreviewState, Any>(
        save = { it.savedFields() },
        restore = { CustomizationPreviewState.fromSavedFields(it) },
    )

@Composable
private fun CustomizationDashboard(
    repository: CustomizationPreviewRepository,
    externalRevision: Int,
    openAdvanced: (Int) -> Unit,
) {
    var scopeChoice by rememberSaveable { mutableIntStateOf(0) }
    val scope =
        when (scopeChoice) {
            1 -> SettingsScope.Target(TargetApp.WHATSAPP)
            2 -> SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)
            else -> SettingsScope.Global
        }
    var revision by remember { mutableIntStateOf(0) }
    var baseline by rememberSaveable(scopeChoice, revision, stateSaver = previewSaver) {
        mutableStateOf(repository.read(scope))
    }
    var draft by rememberSaveable(scopeChoice, revision, stateSaver = previewSaver) {
        mutableStateOf(baseline)
    }

    var baselineFingerprint by rememberSaveable(scopeChoice, revision) {
        mutableStateOf(repository.fingerprint(scope))
    }

    // When the original editor changed values and the preview is CLEAN, resync.
    // When the preview is DIRTY, never discard pending edits on Resume/rotation.
    LaunchedEffect(scopeChoice, externalRevision) {
        if (draft == baseline) {
            val actual = repository.read(scope)
            baseline = actual
            draft = actual
            baselineFingerprint = repository.fingerprint(scope)
        }
    }
    var pendingScope by remember { mutableStateOf<Int?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val isDirty = baseline != draft

    if (pendingScope != null) {
        AlertDialog(
            onDismissRequest = { pendingScope = null },
            title = { Text(stringResource(R.string.uix_discard_title)) },
            text = { Text(stringResource(R.string.uix_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scopeChoice = pendingScope ?: scopeChoice
                    pendingScope = null
                    saveFailed = false
                }) { Text(stringResource(R.string.uix_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingScope = null }) { Text(stringResource(R.string.diagnostics_cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.perso), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.uix_preview_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.uix_global, R.string.uix_whatsapp, R.string.uix_business).forEachIndexed { index, label ->
                FilterChip(
                    selected = scopeChoice == index,
                    onClick = {
                        if (scopeChoice != index) {
                            if (isDirty) {
                                pendingScope = index
                            } else {
                                scopeChoice = index
                                saveFailed = false
                            }
                        }
                    },
                    label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        PreviewPhone(draft)
        Text(
            stringResource(R.string.uix_preview_not_verified),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.uix_preview_colors), style = MaterialTheme.typography.titleMedium)
        PreviewSwitch(stringResource(R.string.colors_customization), draft.colorsEnabled) {
            draft = draft.copy(colorsEnabled = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0xFF008069, 0xFF1661A7, 0xFF8734AB, 0xFFC06414).forEach { value ->
                val colorValue = value.toInt()
                FilterChip(
                    selected = draft.accentColor == colorValue && draft.colorsEnabled,
                    onClick = { draft = draft.copy(accentColor = colorValue, colorsEnabled = true) },
                    label = { Text("●", color = Color(colorValue)) },
                )
            }
        }
        Text(stringResource(R.string.uix_preview_sections), style = MaterialTheme.typography.titleMedium)
        PreviewSwitch(stringResource(R.string.uix_hide_channels), draft.hideChannels) {
            draft = draft.copy(hideChannels = it)
        }
        PreviewSwitch(stringResource(R.string.uix_hide_communities), !draft.showCommunities) {
            draft = draft.withTabHidden("600", it)
        }
        PreviewSwitch(stringResource(R.string.uix_hide_calls), !draft.showCalls) {
            draft = draft.withTabHidden("400", it)
        }
        PreviewSwitch(stringResource(R.string.uix_hide_updates), !draft.showUpdatesTab) {
            draft = draft.withTabHidden("300", it)
        }
        Column {
            Text(stringResource(R.string.uix_hide_status_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.uix_status_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.uix_preview_navigation), style = MaterialTheme.typography.titleMedium)
        PreviewSwitch(stringResource(R.string.floating_bottom_bar), draft.floatingBottomBar) {
            draft = draft.copy(floatingBottomBar = it)
        }
        if (saveFailed) {
            Text(stringResource(R.string.uix_save_conflict), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (repository.save(scope, baseline, draft, baselineFingerprint)) {
                    saveFailed = false
                    revision++
                } else {
                    saveFailed = true
                }
            }, enabled = isDirty) { Text(stringResource(R.string.uix_apply)) }
            OutlinedButton(onClick = {
                draft = baseline
                saveFailed = false
            }, enabled = isDirty) {
                Text(stringResource(R.string.diagnostics_cancel))
            }
            OutlinedButton(onClick = {
                revision++
                saveFailed = false
            }) {
                Text(stringResource(R.string.reload))
            }
        }
        Text(
            stringResource(R.string.uix_apply_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Card(onClick = { openAdvanced(scopeChoice) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        if (scopeChoice ==
                            0
                        ) {
                            R.string.uix_advanced_customization
                        } else {
                            R.string.per_target_settings
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        if (scopeChoice ==
                            0
                        ) {
                            R.string.uix_advanced_summary
                        } else {
                            R.string.uix_target_advanced_summary
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PreviewSwitch(
    title: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChanged(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        WaXFeatureSwitch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun PreviewPhone(state: CustomizationPreviewState) {
    val accent =
        if (state.colorsEnabled && state.accentColor != 0) {
            Color(state.accentColor)
        } else {
            MaterialTheme.colorScheme.primary
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.uix_illustrative_preview), style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().background(accent, RoundedCornerShape(10.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.uix_whatsapp), color = Color.White, fontWeight = FontWeight.Bold)
                Text("⌕  ⋮", color = Color.White)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.uix_preview_chat), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            stringResource(R.string.uix_preview_message),
                            modifier =
                                Modifier
                                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.showUpdatesTab) {
                        Text(stringResource(R.string.uix_preview_updates), style = MaterialTheme.typography.labelLarge)
                        if (state.showStatusSection) {
                            Text(
                                stringResource(R.string.uix_preview_status),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.showChannelsSection) {
                            Text(
                                stringResource(R.string.uix_preview_channels),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(if (state.floatingBottomBar) 26.dp else 8.dp),
                        ).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(stringResource(R.string.uix_preview_chats), style = MaterialTheme.typography.labelSmall)
                if (state.showUpdatesTab) Text(stringResource(R.string.uix_preview_updates), style = MaterialTheme.typography.labelSmall)
                if (state.showCommunities) {
                    Text(
                        stringResource(R.string.uix_preview_communities),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (state.showCalls) Text(stringResource(R.string.uix_preview_calls), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
