package com.thecaticorn01.ircontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.thecaticorn01.ircontroller.ui.theme.DirectionIcons
import com.thecaticorn01.ircontroller.ui.theme.RemoteColors
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind
import com.thecaticorn01.ircontroller.core.model.RemoteLayout

/**
 * Lays out a remote like the physical thing: power row, rockers around a
 * navigation pad, transport controls, number pad and colour keys.
 */
@Composable
fun RemoteKeypad(layout: RemoteLayout, remote: IRRemote?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        // Rockers + D-pad span five round buttons plus padding; shrink buttons on narrow phones.
        val buttonSize = ((maxWidth - 76.dp) / 5).coerceIn(48.dp, 64.dp)
        CompositionLocalProvider(LocalRemoteButtonSize provides buttonSize) {
            KeypadContent(layout, remote)
        }
    }
}

@Composable
private fun KeypadContent(layout: RemoteLayout, remote: IRRemote?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TopRow(layout, remote)
        if (layout.hasDirectionalPad || layout.hasVolume || layout.hasChannel) {
            // Physical remotes keep volume on the left and channel on the right, whatever the language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    if (layout.hasVolume) {
                        Rocker(layout.volumeUp, layout.volumeDown, "VOL", Icons.Rounded.Add, Icons.Rounded.Remove, remote)
                    }
                    if (layout.hasDirectionalPad) DirectionalPad(layout, remote)
                    if (layout.hasChannel) {
                        Rocker(layout.channelUp, layout.channelDown, "CH", Icons.Rounded.KeyboardArrowUp, Icons.Rounded.KeyboardArrowDown, remote)
                    }
                }
            }
        }
        if (layout.navigation.isNotEmpty()) ButtonRow(layout.navigation, remote)
        if (layout.transport.isNotEmpty()) ButtonRow(layout.transport, remote)
        if (layout.hasNumberPad) NumberPad(layout.digits, remote)
        if (layout.colors.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (button in layout.colors) {
                    RemoteButton(button, style = RemoteButtonStyle.ICON, tint = colorFor(button), prominent = true, remote = remote)
                }
            }
        }
        if (layout.climate.isNotEmpty()) ButtonGrid(layout.climate, remote, minCellWidth = 140.dp)
    }
}

private fun colorFor(button: IRButton): Color = when (button.role.kind) {
    Kind.RED -> RemoteColors.red
    Kind.GREEN -> RemoteColors.green
    Kind.YELLOW -> RemoteColors.yellow
    Kind.BLUE -> RemoteColors.blue
    else -> RemoteColors.blue
}

@Composable
private fun TopRow(layout: RemoteLayout, remote: IRRemote?) {
    val others = listOfNotNull(layout.powerOn, layout.powerOff, layout.input, layout.mute)
    if (layout.power == null && others.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        layout.power?.let {
            RemoteButton(it, style = RemoteButtonStyle.ROUND, tint = RemoteColors.power, prominent = true, remote = remote)
        }
        for (button in others) {
            val tint = when (button.role.kind) {
                Kind.POWER_ON -> RemoteColors.on
                Kind.POWER_OFF -> RemoteColors.power
                else -> null
            }
            RemoteButton(button, style = RemoteButtonStyle.ROUND, tint = tint, remote = remote)
        }
    }
}

@Composable
private fun ButtonRow(buttons: List<IRButton>, remote: IRRemote?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (button in buttons) RemoteButton(button, style = RemoteButtonStyle.ICON, remote = remote)
    }
}

/** Up/down rocker such as volume or channel. */
@Composable
private fun Rocker(
    up: IRButton?,
    down: IRButton?,
    title: String,
    upIcon: androidx.compose.ui.graphics.vector.ImageVector,
    downIcon: androidx.compose.ui.graphics.vector.ImageVector,
    remote: IRRemote?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(percent = 50))
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        if (up != null) RemoteButton(up, style = RemoteButtonStyle.ROUND, remote = remote) else MissingButton(upIcon)
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (down != null) RemoteButton(down, style = RemoteButtonStyle.ROUND, remote = remote) else MissingButton(downIcon)
    }
}

/** Four-way navigation pad with a centre OK button. */
@Composable
private fun DirectionalPad(layout: RemoteLayout, remote: IRRemote?) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pad(layout.up, Icons.Rounded.KeyboardArrowUp, remote)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pad(layout.left, DirectionIcons.left, remote)
                if (layout.ok != null) {
                    RemoteButton(layout.ok!!, style = RemoteButtonStyle.ROUND, prominent = true, remote = remote)
                } else {
                    MissingButton(null, label = "OK")
                }
                Pad(layout.right, DirectionIcons.right, remote)
            }
            Pad(layout.down, Icons.Rounded.KeyboardArrowDown, remote)
        }
    }
}

@Composable
private fun Pad(button: IRButton?, icon: androidx.compose.ui.graphics.vector.ImageVector, remote: IRRemote?) {
    if (button != null) RemoteButton(button, style = RemoteButtonStyle.ROUND, remote = remote) else MissingButton(icon)
}

/** Telephone-style 1–9, 0 keypad. */
@Composable
private fun NumberPad(digits: Map<Int, IRButton>, remote: IRRemote?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (column in 1..3) Digit(digits, row * 3 + column, remote)
            }
        }
        Digit(digits, 0, remote)
    }
}

@Composable
private fun Digit(digits: Map<Int, IRButton>, digit: Int, remote: IRRemote?) {
    val button = digits[digit]
    if (button != null) RemoteButton(button, style = RemoteButtonStyle.DIGIT, remote = remote)
    else MissingButton(null, label = "$digit")
}

/** Adaptive grid of standard buttons that works inside a scrolling column. */
@Composable
fun ButtonGrid(
    buttons: List<IRButton>,
    remote: IRRemote?,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 150.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / minCellWidth).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in buttons.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (button in row) RemoteButton(button, remote = remote, modifier = Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Centres content at a comfortable reading width on large screens. */
@Composable
fun CenteredColumn(maxWidth: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}
