package com.wax.module.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.wax.module.ModuleApplication
import com.wax.module.R
import com.wax.module.activities.base.BaseActivity
import com.wax.module.ui.theme.WaXTheme

/** Uses the existing thememode preference, rather than a second Manager theme store. */
class ManagerSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleApplication.changeLanguage(this)
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent { WaXTheme { SettingsPage() } }
        })
    }

    private fun navigateToLegacy(key: String, parent: String = "general_home") {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to_fragment", 1)
                putExtra("scroll_to_preference", key)
                putExtra("parent_preference", parent)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
    }

    @Composable
    private fun SettingsPage() {
        val prefs = remember { PreferenceManager.getDefaultSharedPreferences(this) }
        var mode by remember { mutableIntStateOf(prefs.getString("thememode", "0")?.toIntOrNull() ?: 0) }
        Scaffold { inset ->
            Column(
                modifier = Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.uix_back)) }
                Text(stringResource(R.string.uix_app_settings), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium)
                listOf(
                    0 to R.string.uix_theme_system,
                    1 to R.string.uix_theme_dark,
                    2 to R.string.uix_theme_light,
                ).forEach { (value, title) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            mode = value
                            prefs.edit().putString("thememode", value.toString()).apply()
                            ModuleApplication.setThemeMode(value)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == value, onClick = {
                            mode = value
                            prefs.edit().putString("thememode", value.toString()).apply()
                            ModuleApplication.setThemeMode(value)
                        })
                        Text(stringResource(title), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider()
                Card(onClick = { navigateToLegacy("app_language") }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.app_language), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.uix_language_summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(onClick = { navigateToLegacy("update_check") }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.update_check), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.uix_updates_summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(onClick = { navigateToLegacy("enablelogs") }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.verbose_logs), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.uix_logs_summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { startActivity(Intent(this@ManagerSettingsActivity, AboutActivity::class.java)) }) {
                    Text(stringResource(R.string.about))
                }
            }
        }
    }
}
