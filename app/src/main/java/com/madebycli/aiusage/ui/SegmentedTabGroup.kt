// Adapted from Marco-9456/AniSync (GPL-3.0).
package com.madebycli.aiusage.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Connected toggle-button group. With [fillEqually] the buttons share the full width; otherwise the
 * row scrolls horizontally, which suits an open-ended number of options. State is hoisted.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SegmentedTabGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: @Composable (T) -> ImageVector? = { null },
    fillEqually: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    scrollState: ScrollState = rememberScrollState()
) {
    val haptic = LocalHapticFeedback.current

    val rowModifier = if (fillEqually) modifier.fillMaxWidth() else modifier.horizontalScroll(scrollState)

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }

            ToggleButton(
                checked = selected == option,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(option)
                },
                modifier = if (fillEqually) Modifier.weight(1f) else Modifier,
                shapes = shapes
            ) {
                icon(option)?.let { vector ->
                    Icon(imageVector = vector, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label(option),
                    style = labelStyle,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
