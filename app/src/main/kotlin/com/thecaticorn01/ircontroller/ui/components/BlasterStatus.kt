package com.thecaticorn01.ircontroller.ui.components

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.blaster.BlasterManager.Activity
import com.thecaticorn01.ircontroller.blaster.BlasterManager.State
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartModel
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartPayloadEncoding
import com.thecaticorn01.ircontroller.core.elksmart.hexString
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val ReadyGreen = Color(0xFF2E9E5B)
private val ConnectingOrange = Color(0xFFE38A1E)

@Composable
private fun statusColor(state: State): Color = when (state) {
    is State.Ready -> ReadyGreen
    is State.Connecting, is State.NeedsPermission -> ConnectingOrange
    State.Searching -> MaterialTheme.colorScheme.onSurfaceVariant
    is State.Failed -> MaterialTheme.colorScheme.error
}

private fun statusIcon(state: State, activity: Activity): ImageVector = when (state) {
    is State.Ready -> Icons.Rounded.WifiTethering
    is State.Connecting -> Icons.Rounded.Cable
    is State.NeedsPermission -> Icons.Rounded.Key
    State.Searching -> Icons.Rounded.SensorsOff
    is State.Failed -> Icons.Rounded.ErrorOutline
}

fun statusTitle(state: State): String = when (state) {
    State.Searching -> "No IR blaster"
    is State.NeedsPermission -> "Allow USB access"
    is State.Connecting -> "Connecting…"
    is State.Ready -> state.info.model.name.removePrefix("ELK Smart ")
    is State.Failed -> "Blaster error"
}

fun statusSubtitle(state: State, activity: Activity, lastSent: String?): String {
    when (activity) {
        is Activity.Sending -> return "Sending ${activity.name}…"
        Activity.Learning -> return "Listening for a remote…"
        Activity.Idle -> {}
    }
    return when (state) {
        State.Searching -> "Plug in an ELK Smart USB-C blaster"
        is State.NeedsPermission -> "Tap to let the app use ${state.deviceName}"
        is State.Connecting -> state.name
        is State.Ready -> lastSent?.let { "Last sent: $it" } ?: "Ready"
        is State.Failed -> state.message
    }
}

/** Coloured status icon that bounces on every send and pulses while busy. */
@Composable
fun StatusIndicator(state: State, activity: Activity, sendCount: Int, modifier: Modifier = Modifier) {
    val color = statusColor(state)
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(sendCount) {
        if (sendCount > 0) {
            bounce.snapTo(1.25f)
            bounce.animateTo(1f)
        }
    }
    val busy = activity == Activity.Learning || state is State.Connecting
    val pulse = rememberInfiniteTransition(label = "status-pulse")
    val alpha by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
    Box(
        modifier
            .size(32.dp)
            .background(color.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            statusIcon(state, activity),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp).scale(bounce.value).alpha(if (busy) alpha else 1f),
        )
    }
}

/** Top-bar button showing the blaster state; opens the details sheet. */
@Composable
fun BlasterStatusButton() {
    val blaster = LocalContext.current.container.blaster
    val state by blaster.state.collectAsStateWithLifecycle()
    val activity by blaster.activity.collectAsStateWithLifecycle()
    val sendCount by blaster.sendCount.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(statusTitle(state)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = { showDetails = true }) {
            StatusIndicator(state, activity, sendCount)
        }
    }
    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            BlasterDetails(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp))
        }
    }
}

/** Connection details, troubleshooting and the activity log. */
@Composable
fun BlasterDetails(modifier: Modifier = Modifier, showLog: Boolean = true) {
    val context = LocalContext.current
    val blaster = context.container.blaster
    val state by blaster.state.collectAsStateWithLifecycle()
    val activity by blaster.activity.collectAsStateWithLifecycle()
    val sendCount by blaster.sendCount.collectAsStateWithLifecycle()
    val lastSent by blaster.lastSentName.collectAsStateWithLifecycle()
    val log by blaster.log.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIndicator(state, activity, sendCount)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(statusTitle(state), style = MaterialTheme.typography.titleMedium)
                Text(
                    statusSubtitle(state, activity, lastSent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is State.Connecting) LoadingIndicator(Modifier.size(32.dp))
        }

        when (val s = state) {
            is State.Ready -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Model", s.info.model.name)
                    DetailRow("USB ID", "%04X:%04X".format(ElkSmartModel.VENDOR_ID, s.info.model.productId))
                    s.info.serialNumber?.let { DetailRow("Serial", it) }
                    DetailRow(
                        "Protocol",
                        if (s.info.encoding == ElkSmartPayloadEncoding.HUFFMAN) "Huffman-coded (newer firmware)" else "Plain",
                    )
                    DetailRow("Identity", s.info.identity.hexString)
                    DetailRow("Transport", s.info.endpoints)
                }
            }
            State.Searching -> Text(
                "Plug your ELK Smart USB-C IR blaster into your phone or tablet’s USB-C port. If you use a hub or adapter, " +
                    "try connecting it directly. Your device must support USB host (OTG). If it still isn’t found, share the " +
                    "diagnostic report below in a bug report.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is State.NeedsPermission -> Text(
                "Android needs your permission before the app can use the blaster. Tap Allow access, then choose OK. " +
                    "Tick “Always open” to skip this step next time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is State.Connecting -> {}
            is State.Failed -> Text(
                "Unplug the blaster, wait a few seconds, plug it back in, then tap Reconnect. Some dongles are picky about cables and hubs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is State.NeedsPermission) {
                AssistChip(onClick = { blaster.requestPermission() }, label = { Text("Allow access") }, leadingIcon = { Icon(Icons.Rounded.Key, null) })
            }
            AssistChip(onClick = { blaster.reconnect() }, label = { Text("Reconnect") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) })
            AssistChip(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Diagnostic report", blaster.diagnosticReport())))
                        copied = true
                    }
                },
                label = { Text(if (copied) "Copied" else "Copy diagnostic report") },
                leadingIcon = { Icon(Icons.Rounded.MedicalServices, null) },
            )
            AssistChip(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "IR Controller diagnostic report")
                        .putExtra(Intent.EXTRA_TEXT, blaster.diagnosticReport())
                    context.startActivity(Intent.createChooser(send, "Share diagnostic report"))
                },
                label = { Text("Share report") },
                leadingIcon = { Icon(Icons.Rounded.Share, null) },
            )
        }

        if (showLog && log.isNotEmpty()) {
            HorizontalDivider()
            Text("Activity", style = MaterialTheme.typography.titleSmall)
            val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (entry in log.take(30)) {
                    Row {
                        Text(
                            format.format(Date(entry.timeMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (label == "Identity") FontFamily.Monospace else null)
    }
}
