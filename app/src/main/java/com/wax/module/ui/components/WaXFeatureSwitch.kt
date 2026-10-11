package com.wax.module.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Single visual/semantic switch for the new Manager surfaces.
 *
 * The user-approved physical visual direction is independent from language:
 * ON thumb stays on the physical right; OFF thumb stays on the left. The
 * surrounding row and its text remain RTL in Arabic.
 *
 * This is strictly a UI control, not proof of a hooked feature running.
 */
@Composable
fun WaXFeatureSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFF208B4D),
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF6E7773),
                    uncheckedThumbColor = Color.White,
                ),
        )
    }
}
