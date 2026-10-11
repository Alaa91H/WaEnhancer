package com.wax.module.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.wax.module.R
import com.wax.module.activities.AboutActivity
import com.wax.module.activities.DiagnosticsActivity
import com.wax.module.activities.MainActivity
import com.wax.module.activities.SearchActivity
import com.wax.module.ui.targets.TargetSettingsActivity
import com.wax.module.ui.theme.WaXTheme

/** Routes to established tools rather than introducing alternative diagnostics or settings stores. */
class ToolsHubFragment : Fragment() {
    private data class Tool(val title: Int, val summary: Int, val action: Int)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WaXTheme { ToolsScreen(::openTool) } }
        }

    private fun openTool(action: Int) {
        val host = requireActivity()
        when (action) {
            0 -> startActivity(Intent(host, DiagnosticsActivity::class.java))
            1 -> startActivity(Intent(host, TargetSettingsActivity::class.java))
            2 -> (host as? MainActivity)?.navigateToLegacyFragment(0)
            3 -> startActivity(Intent(host, SearchActivity::class.java))
            4 -> startActivity(Intent(host, AboutActivity::class.java))
            5 -> (host as? MainActivity)?.navigateToLegacyFragment(1, "update_check", "general_home")
        }
    }

    @Composable
    private fun ToolsScreen(onOpen: (Int) -> Unit) {
        val tiles = listOf(
            Tool(R.string.diagnostics_title, R.string.uix_tool_diagnostics_summary, 0),
            Tool(R.string.uix_compatibility, R.string.uix_tool_target_summary, 1),
            Tool(R.string.uix_backup, R.string.uix_tool_home_summary, 2),
            Tool(R.string.search_features_title, R.string.uix_tool_search_summary, 3),
            Tool(R.string.about, R.string.uix_tool_about_summary, 4),
            Tool(R.string.updates, R.string.uix_tool_updates_summary, 5),
        )
        Scaffold { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(2) }) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        Text(stringResource(R.string.uix_tools), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.uix_tools_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(tiles) { tile ->
                    Card(onClick = { onOpen(tile.action) }, modifier = Modifier.fillMaxWidth()) {
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
