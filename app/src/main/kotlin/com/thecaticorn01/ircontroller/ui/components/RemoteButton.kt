package com.thecaticorn01.ircontroller.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thecaticorn01.ircontroller.ui.theme.icon
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote

/** Diameter of round keypad buttons; the keypad shrinks it on narrow screens. */
val LocalRemoteButtonSize = androidx.compose.runtime.compositionLocalOf { 64.dp }

enum class RemoteButtonStyle {
    /** Name, with an icon when the role has one. */
    STANDARD,
    /** Icon only (falls back to the name). */
    ICON,
    /** Large circular icon button. */
    ROUND,
    /** Number keypad digit. */
    DIGIT,
}

/**
 * A single remote button. Tap to send; long-press for more actions. The shape
 * squeezes while pressed (Material 3 Expressive) and the device gives haptic feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteButton(
    button: IRButton,
    modifier: Modifier = Modifier,
    style: RemoteButtonStyle = RemoteButtonStyle.STANDARD,
    tint: Color? = null,
    prominent: Boolean = false,
    /** When set, the menu offers editing actions for this remote. */
    remote: IRRemote? = null,
) {
    val actions = LocalRemoteActions.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menuOpen by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press-scale")
    val round = style == RemoteButtonStyle.ROUND || style == RemoteButtonStyle.DIGIT
    val corner by animateIntAsState(if (pressed) (if (round) 32 else 24) else 50, label = "press-shape")

    val container = when {
        prominent -> tint ?: MaterialTheme.colorScheme.primary
        tint != null -> tint.copy(alpha = 0.18f)
        style == RemoteButtonStyle.DIGIT -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        prominent && tint != null -> Color.White
        prominent -> MaterialTheme.colorScheme.onPrimary
        tint != null -> tint
        else -> contentColorFor(container).takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSecondaryContainer
    }

    val roundSize = LocalRemoteButtonSize.current
    val sizeModifier = when (style) {
        RemoteButtonStyle.ROUND -> Modifier.size(roundSize)
        RemoteButtonStyle.DIGIT -> Modifier.size(roundSize)
        RemoteButtonStyle.ICON -> Modifier.width(60.dp).height(48.dp)
        RemoteButtonStyle.STANDARD -> Modifier.fillMaxWidth().height(52.dp)
    }

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(percent = corner),
            color = container,
            contentColor = content,
            modifier = sizeModifier
                .scale(scale)
                .semantics { contentDescription = button.displayName }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = androidx.compose.material3.ripple(),
                    role = Role.Button,
                    onLongClickLabel = "More actions",
                    onLongClick = {
                        if (actions.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = {
                        if (actions.hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        actions.send(button)
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ButtonLabel(button, style)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                button.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                button.signal.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Send") },
                leadingIcon = { Icon(Icons.Rounded.SettingsRemote, null) },
                onClick = { menuOpen = false; actions.send(button) },
            )
            DropdownMenuItem(
                text = { Text("Send as long press") },
                leadingIcon = { Icon(Icons.Rounded.TouchApp, null) },
                onClick = { menuOpen = false; actions.send(button, extraRepeats = 5) },
            )
            DropdownMenuItem(
                text = { Text("Copy Pronto hex") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                onClick = { menuOpen = false; actions.copyPronto(button) },
            )
            if (remote != null) {
                DropdownMenuItem(
                    text = { Text("Add to home screen") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, null) },
                    onClick = { menuOpen = false; actions.pinButton(remote, button) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Edit button") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; actions.editButton(remote.id, button.id) },
                )
                DropdownMenuItem(
                    text = { Text("Delete button") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menuOpen = false; actions.deleteButton(remote.id, button.id) },
                )
            }
        }
    }
}

@Composable
private fun ButtonLabel(button: IRButton, style: RemoteButtonStyle) {
    val icon = button.role.icon
    when (style) {
        RemoteButtonStyle.ICON, RemoteButtonStyle.ROUND -> {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(if (style == RemoteButtonStyle.ROUND) 28.dp else 24.dp))
            } else {
                Text(
                    button.displayName,
                    style = if (style == RemoteButtonStyle.ROUND) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        RemoteButtonStyle.DIGIT -> Text(
            button.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        RemoteButtonStyle.STANDARD -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                button.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Placeholder that keeps keypad geometry when a remote lacks a button. */
@Composable
fun MissingButton(icon: androidx.compose.ui.graphics.vector.ImageVector?, size: Dp = LocalRemoteButtonSize.current, label: String? = null) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        else if (label != null) Text(label, color = color, style = MaterialTheme.typography.headlineSmall)
    }
}
