package com.wax.module.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wax.module.ui.theme.LocalManagerReducedMotion
import com.wax.module.ui.theme.ManagerMotionPreference

/**
 * Accessible Manager switch: checked thumb always physically right (green);
 * unchecked always left (grey), independent of surrounding text direction.
 * The user-selected reduced-motion mode has no animated movement.
 */
@Composable
fun WaXFeatureSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalManagerReducedMotion.current) {
        Canvas(
            modifier =
                modifier
                    .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
                    .size(width = 52.dp, height = 48.dp)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    ),
        ) {
            val trackHeight = 30.dp.toPx()
            val centerY = size.height / 2f
            val radius = 12.dp.toPx()
            val inset = 3.dp.toPx()
            drawRoundRect(
                color = if (checked) Color(0xFF208B4D) else Color(0xFF6E7773),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center =
                    Offset(
                        ManagerMotionPreference.physicalThumbX(checked, size.width, inset + radius),
                        centerY,
                    ),
            )
        }
    } else {
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
}
