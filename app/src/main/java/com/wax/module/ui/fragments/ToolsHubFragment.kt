package com.wax.module.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.wax.module.R
import com.wax.module.activities.DiagnosticsActivity
import com.wax.module.activities.MainActivity
import com.wax.module.ui.theme.WaXTheme

/**
 * The six approved tools are routes to existing implementations, not six
 * replacement diagnostic engines. Compatibility, log export and safe recovery
 * never invent evidence or untested repair operations.
 */
class ToolsHubFragment : Fragment() {
    private data class Tool(
        val title: Int,
        val summary: Int,
        val action: ToolAction,
    )

    private fun openTool(action: ToolAction) {
        val host = requireActivity()
        when (action) {
            ToolAction.DIAGNOSTICS,
            ToolAction.COMPATIBILITY,
            ToolAction.SANITIZED_REPORTS,
            ToolAction.RECOVERY,
            -> {
                startActivity(Intent(host, DiagnosticsActivity::class.java))
            }

            ToolAction.BACKUPS -> {
                (host as? MainActivity)?.openBackupActions()
            }

            ToolAction.UPDATES -> {
                (host as? MainActivity)?.navigateToLegacyFragment(1, "update_check", "general_home")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            id = R.id.manager_tools_compose_root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WaXTheme { ToolsScreen(::openTool) } }
        }

    @Composable
    private fun ToolsScreen(onOpen: (ToolAction) -> Unit) {
        val tiles =
            listOf(
                Tool(R.string.diagnostics_title, R.string.uix_tool_diagnostics_summary, ToolAction.DIAGNOSTICS),
                Tool(R.string.uix_compatibility, R.string.uix_compat_evidence_summary, ToolAction.COMPATIBILITY),
                Tool(R.string.uix_backup, R.string.uix_tool_home_summary, ToolAction.BACKUPS),
                Tool(R.string.updates, R.string.uix_tool_updates_summary, ToolAction.UPDATES),
                Tool(R.string.uix_sanitized_reports, R.string.uix_sanitized_reports_summary, ToolAction.SANITIZED_REPORTS),
                Tool(R.string.uix_recovery, R.string.uix_recovery_summary, ToolAction.RECOVERY),
            )
        var confirmation by remember { mutableStateOf<ToolAction?>(null) }

        if (confirmation != null) {
            val chosen = confirmation
            AlertDialog(
                onDismissRequest = { confirmation = null },
                title = {
                    Text(
                        stringResource(
                            if (chosen == ToolAction.RECOVERY) R.string.uix_recovery else R.string.uix_compatibility,
                        ),
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (chosen == ToolAction.RECOVERY) {
                                R.string.uix_recovery_information
                            } else {
                                R.string.uix_compat_information
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmation = null
                        if (chosen != null) onOpen(chosen)
                    }) { Text(stringResource(R.string.uix_open_diagnostics)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmation = null }) {
                        Text(stringResource(R.string.diagnostics_cancel))
                    }
                },
            )
        }

        Scaffold { padding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Adaptive rather than a fixed tablet-width pair of enormous cards.
                val gridColumns = if (maxWidth >= 700.dp) 3 else 2
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { GridItemSpan(gridColumns) }) {
                        Column(modifier = Modifier.padding(4.dp)) {
                            Text(stringResource(R.string.uix_tools), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.uix_tools_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(tiles, key = { it.action }) { tile ->
                        Card(
                            onClick = {
                                if (tile.action.requiresExplanation) {
                                    confirmation = tile.action
                                } else {
                                    onOpen(tile.action)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(tile.title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(tile.summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Stable named actions prevent misleading IDs from drifting between list and route. */
internal enum class ToolAction(
    val requiresExplanation: Boolean = false,
) {
    DIAGNOSTICS,
    COMPATIBILITY(true),
    BACKUPS,
    UPDATES,
    SANITIZED_REPORTS,
    RECOVERY(true),
}
