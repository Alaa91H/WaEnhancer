package com.wax.module.ui.profiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.wax.module.R
import com.wax.module.modern.ControlCenterProfiles
import com.wax.module.modern.ModernRuntimePreferenceRelay
import com.wax.module.modern.ModernTargetStateClient
import com.wax.module.ui.theme.WaXTheme

/** Manager-side entry for the same profile data exposed to the injected panel. */
class ControlCenterProfilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WaXTheme { ProfilesScreen() } }
    }
}

private enum class Action { CREATE, DUPLICATE, RENAME, DELETE, SELECT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val state = remember(refresh) { ControlCenterProfiles.read(prefs) }
    var action by remember { mutableStateOf<Action?>(null) }
    var chosenId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    fun runOperation(operation: () -> Boolean) {
        val ok = operation()
        failed = !ok
        if (ok) {
            runCatching { ModernRuntimePreferenceRelay.requestSync() }
            runCatching {
                context.contentResolver.notifyChange(ModernTargetStateClient.STATES_URI, null)
            }
            refresh++
        }
        action = null
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.control_profiles_title)) })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.control_profiles_scope),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (failed || state.corrupted) {
                Text(stringResource(R.string.control_profiles_failure),
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { chosenId = ""; name = ""; action = Action.CREATE },
                enabled = !state.corrupted && state.profiles.size < 16,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.control_profiles_create)) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (profile.id == ControlCenterProfiles.DEFAULT_ID)
                                    stringResource(R.string.control_profiles_default)
                                else profile.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (profile.id == state.activeId) {
                                Text(stringResource(R.string.control_profiles_active),
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Row {
                                if (profile.id != state.activeId) {
                                    TextButton(onClick = {
                                        chosenId = profile.id
                                        action = Action.SELECT
                                    }) { Text(stringResource(R.string.control_profiles_select)) }
                                }
                                TextButton(onClick = {
                                    chosenId = profile.id
                                    name = profile.name + " (copy)"
                                    action = Action.DUPLICATE
                                }) { Text(stringResource(R.string.control_profiles_duplicate)) }
                            }
                            if (profile.id != ControlCenterProfiles.DEFAULT_ID) {
                                Row {
                                    TextButton(onClick = {
                                        chosenId = profile.id
                                        name = profile.name
                                        action = Action.RENAME
                                    }) { Text(stringResource(R.string.control_profiles_rename)) }
                                    TextButton(
                                        enabled = profile.id != state.activeId,
                                        onClick = { chosenId = profile.id; action = Action.DELETE },
                                    ) {
                                        Text(stringResource(R.string.control_profiles_delete),
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedAction = action
    if (selectedAction != null) {
        val needsName = selectedAction in setOf(Action.CREATE, Action.DUPLICATE, Action.RENAME)
        val title = when (selectedAction) {
            Action.CREATE -> R.string.control_profiles_create
            Action.DUPLICATE -> R.string.control_profiles_duplicate
            Action.RENAME -> R.string.control_profiles_rename
            Action.DELETE -> R.string.control_profiles_delete
            Action.SELECT -> R.string.control_profiles_select
        }
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(stringResource(title)) },
            text = {
                if (needsName) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.control_profiles_name)) },
                        singleLine = true,
                    )
                } else {
                    Text(stringResource(R.string.control_profiles_scope))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !needsName || name.isNotBlank(),
                    onClick = {
                        runOperation {
                            when (selectedAction) {
                                Action.CREATE -> ControlCenterProfiles.create(prefs, name)
                                Action.DUPLICATE -> ControlCenterProfiles.duplicate(prefs, chosenId, name)
                                Action.RENAME -> ControlCenterProfiles.rename(prefs, chosenId, name)
                                Action.DELETE -> ControlCenterProfiles.delete(prefs, chosenId)
                                Action.SELECT -> ControlCenterProfiles.select(prefs, chosenId)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.control_profiles_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { action = null }) {
                    Text(stringResource(R.string.control_profiles_cancel))
                }
            },
        )
    }
}
