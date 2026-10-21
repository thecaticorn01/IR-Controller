package com.thecaticorn01.ircontroller.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thecaticorn01.ircontroller.blaster.BlasterManager
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartException
import com.thecaticorn01.ircontroller.core.formats.IRCodeImporter
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolDecoder
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/** Draws an infrared signal as a square wave (marks high, spaces low). */
@Composable
fun Waveform(
    signal: RawSignal,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    /** Spaces longer than this are drawn compressed so the interesting part stays visible. */
    maxGap: Int = 12_000,
) {
    val timings = remember(signal) { signal.timings.mapIndexed { i, v -> if (i % 2 == 1) minOf(v, maxGap) else v } }
    Canvas(modifier.semantics { contentDescription = "Waveform with ${signal.timings.size} timings" }) {
        val total = max(1, timings.sum()).toFloat()
        val high = size.height * 0.15f
        val low = size.height * 0.85f
        val path = Path().apply {
            moveTo(0f, low)
            var x = 0f
            timings.forEachIndexed { index, duration ->
                val y = if (index % 2 == 0) high else low
                lineTo(x, y)
                x += duration / total * size.width
                lineTo(x, y)
            }
            lineTo(x, low)
            lineTo(size.width, low)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, low)
            lineTo(0f, low)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(tint.copy(alpha = 0.35f), tint.copy(alpha = 0.05f)), startY = high, endY = low))
        drawPath(path, tint, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
        drawLine(tint.copy(alpha = 0.2f), Offset(0f, low), Offset(size.width, low))
    }
}

/** Compact description of a signal: waveform, protocol, carrier and length. */
@Composable
fun SignalSummary(signal: IRSignal, modifier: Modifier = Modifier) {
    val raw = remember(signal) { runCatching { signal.rendered(toggle = false) }.getOrNull() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (raw != null) {
            Waveform(raw, Modifier.fillMaxWidth().height(54.dp))
            val protocolText = when (signal) {
                is IRSignal.Parsed -> "${signal.signal.protocolName} 0x%X / 0x%X".format(signal.signal.address, signal.signal.command)
                is IRSignal.Raw -> IRProtocolDecoder.decode(signal.signal)?.let { "Raw (looks like ${it.protocolName})" } ?: "Raw"
            }
            Text(
                "$protocolText · ${String.format(Locale.ROOT, "%.1f kHz", raw.frequency / 1000.0)} · ${String.format(Locale.ROOT, "%.0f ms", raw.duration / 1000.0)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("This protocol can’t be transmitted yet.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private sealed interface CapturePhase {
    data object Idle : CapturePhase
    data class Listening(val startedMillis: Long) : CapturePhase
    data class Captured(val raw: RawSignal) : CapturePhase
    data class Failed(val message: String) : CapturePhase
}

/**
 * Drives the blaster's learning mode and shows the captured waveform.
 * The captured (optionally protocol-decoded) signal is reported through [onSignal].
 * Changing [sessionKey] restarts capture (used by the learning wizard).
 */
@Composable
fun SignalCapture(
    buttonName: String,
    onSignal: (IRSignal?) -> Unit,
    modifier: Modifier = Modifier,
    autoStart: Boolean = false,
    sessionKey: Any = Unit,
) {
    val container = LocalContext.current.container
    val blaster = container.blaster
    val state by blaster.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var phase by remember(sessionKey) { mutableStateOf<CapturePhase>(CapturePhase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var current by remember(sessionKey) { mutableStateOf<IRSignal?>(null) }
    val isReady = state is BlasterManager.State.Ready

    fun publish(raw: RawSignal, preferDecoded: Boolean) {
        val decoded = if (preferDecoded) IRProtocolDecoder.decode(raw) else null
        current = decoded?.let { IRSignal.Parsed(it) } ?: IRSignal.Raw(raw)
        onSignal(current)
    }

    fun start() {
        job?.cancel()
        current = null
        onSignal(null)
        phase = CapturePhase.Listening(System.currentTimeMillis())
        val timeout = settings.learnTimeoutSeconds * 1000L
        val frequency = settings.learnFrequency
        job = scope.launch {
            phase = try {
                val raw = blaster.learn(timeout, frequency)
                publish(raw, container.settings.settings.value.preferDecoded)
                CapturePhase.Captured(raw)
            } catch (e: ElkSmartException.LearnCancelled) {
                CapturePhase.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CapturePhase.Failed(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        if (phase is CapturePhase.Listening) {
            blaster.cancelLearning()
            phase = CapturePhase.Idle
        }
        job?.cancel()
        job = null
    }

    LaunchedEffect(sessionKey) {
        if (autoStart && isReady) start()
    }
    DisposableEffect(sessionKey) { onDispose { stop() } }

    val tint by animateColorAsState(
        when (phase) {
            CapturePhase.Idle -> MaterialTheme.colorScheme.primary
            is CapturePhase.Listening -> MaterialTheme.colorScheme.tertiary
            is CapturePhase.Captured -> Color(0xFF2E9E5B)
            is CapturePhase.Failed -> MaterialTheme.colorScheme.error
        },
        label = "capture-tint",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
    ) {
        AnimatedContent(phase, label = "capture-phase", modifier = Modifier.padding(20.dp)) { p ->
            when (p) {
                CapturePhase.Idle -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.WifiTethering, null, tint = tint, modifier = Modifier.size(44.dp))
                    Text("Learn “$buttonName” from your remote", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        "Hold the remote about 1 cm from the end of the IR blaster, tap Start listening, then press the button.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = ::start, enabled = isReady) {
                        Icon(Icons.Rounded.Hearing, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start listening")
                    }
                    if (!isReady) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SensorsOff, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Connect your IR blaster to learn codes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is CapturePhase.Listening -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(p) { while (true) { delay(250); now = System.currentTimeMillis() } }
                    val pulse = rememberInfiniteTransition(label = "listening")
                    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
                    val remaining = max(0, settings.learnTimeoutSeconds - ((now - p.startedMillis) / 1000).toInt())
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.WifiTethering, null, tint = tint, modifier = Modifier.size(48.dp).alpha(alpha))
                        Text("Press “$buttonName” on your remote", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                        Text("Listening… ${remaining}s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = ::stop) { Text("Cancel") }
                    }
                }
                is CapturePhase.Captured -> {
                    val decoded = remember(p.raw) { IRProtocolDecoder.decode(p.raw) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = tint)
                            Spacer(Modifier.width(8.dp))
                            Text("Captured", style = MaterialTheme.typography.titleMedium, color = tint, modifier = Modifier.weight(1f))
                            if (decoded != null) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            "${decoded.protocolName} · 0x%X / 0x%X".format(decoded.address, decoded.command),
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                )
                            }
                        }
                        Waveform(p.raw, Modifier.fillMaxWidth().height(64.dp), tint = tint)
                        if (decoded != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Store as a clean ${decoded.protocolName} code instead of the raw recording",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = settings.preferDecoded,
                                    onCheckedChange = {
                                        container.settings.setPreferDecoded(it)
                                        publish(p.raw, it)
                                    },
                                )
                            }
                        }
                        Row {
                            val actions = LocalRemoteActions.current
                            FilledTonalButton(onClick = { current?.let { actions.send(IRButton(name = buttonName, signal = it)) } }) {
                                Icon(Icons.Rounded.SettingsRemote, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Test")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::start, enabled = isReady) {
                                Icon(Icons.Rounded.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Learn again")
                            }
                        }
                    }
                }
                is CapturePhase.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = tint, modifier = Modifier.size(40.dp))
                    Text(p.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = ::start, enabled = isReady) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }
            }
        }
    }
}

/** Enter a code by protocol + address/command, or paste Pronto / sendir / raw timings. */
@Composable
fun ManualCodeEditor(
    initial: IRSignal?,
    onSignal: (IRSignal?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialParsed = (initial as? IRSignal.Parsed)?.signal
    val initialRaw = (initial as? IRSignal.Raw)?.signal
    var pasteMode by rememberSaveable { mutableStateOf(initialRaw != null) }
    var protocolName by rememberSaveable {
        mutableStateOf(initialParsed?.let { IRProtocolRegistry.canonicalName(it.protocolName) ?: it.protocolName } ?: "NEC")
    }
    var addressText by rememberSaveable { mutableStateOf(initialParsed?.let { "0x%X".format(it.address) } ?: "0x00") }
    var commandText by rememberSaveable { mutableStateOf(initialParsed?.let { "0x%X".format(it.command) } ?: "0x00") }
    var subdeviceText by rememberSaveable { mutableStateOf(initialParsed?.subdevice?.let { "0x%X".format(it) } ?: "") }
    var pastedText by rememberSaveable { mutableStateOf(initialRaw?.timings?.joinToString(" ") ?: "") }

    val result: Pair<IRSignal?, String?> = remember(pasteMode, protocolName, addressText, commandText, subdeviceText, pastedText) {
        if (!pasteMode) {
            val address = parseNumber(addressText)
            val command = parseNumber(commandText)
            if (address == null || command == null) {
                null to "Enter the address and command as decimal or hex (0x…) numbers."
            } else {
                val subdevice = if (subdeviceText.isBlank()) null else parseNumber(subdeviceText)
                IRSignal.Parsed(ParsedSignal(protocolName, address, command, subdevice)) to null
            }
        } else if (pastedText.isBlank()) {
            null to null
        } else {
            try {
                IRSignal.Raw(IRCodeImporter.parse(pastedText).first) to null
            } catch (e: Exception) {
                null to e.message
            }
        }
    }
    LaunchedEffect(result.first) { onSignal(result.first) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !pasteMode, onClick = { pasteMode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Protocol") }
            SegmentedButton(
                selected = pasteMode, onClick = { pasteMode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Paste code") }
        }
        if (!pasteMode) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = protocolName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Protocol") },
                    supportingText = {
                        IRProtocolRegistry.info(protocolName)?.let { Text("${it.summary} · ${it.frequency / 1000} kHz") }
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (info in IRProtocolRegistry.protocols) {
                        DropdownMenuItem(
                            text = { Text(info.name) },
                            onClick = { protocolName = info.name; expanded = false },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = addressText, onValueChange = { addressText = it }, label = { Text("Address") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = commandText, onValueChange = { commandText = it }, label = { Text("Command") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
            OutlinedTextField(
                value = subdeviceText, onValueChange = { subdeviceText = it }, label = { Text("Sub-device (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        } else {
            OutlinedTextField(
                value = pastedText,
                onValueChange = { pastedText = it },
                label = { Text("Code") },
                placeholder = { Text("0000 006D 0022 0002 0157 00AC …") },
                supportingText = { Text("Pronto hex, Global Caché sendir, or mark/space timings in microseconds.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val (signal, message) = result
        if (signal != null) {
            SignalSummary(signal)
        } else if (message != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Parses "0x1F", "1Fh" or "31". */
fun parseNumber(text: String): Long? {
    val trimmed = text.trim().lowercase(Locale.ROOT)
    val value = when {
        trimmed.startsWith("0x") -> trimmed.drop(2).toLongOrNull(16)
        trimmed.endsWith("h") -> trimmed.dropLast(1).toLongOrNull(16)
        else -> trimmed.toLongOrNull()
    }
    return value?.takeIf { it in 0..0xFFFF_FFFFL }
}
