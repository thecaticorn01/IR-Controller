package com.thecaticorn01.ircontroller.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.blaster.BlasterManager
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.theme.RemoteColors
import com.thecaticorn01.ircontroller.core.database.RemoteImport
import kotlinx.coroutines.delay
import java.util.Locale

private const val MAX_CANDIDATES = 300

/**
 * Walks through the current search results sending each remote's power
 * button, so the user can find a working code without knowing the model.
 */
@Composable
fun PowerScanScreen(onDone: () -> Unit) {
    val app = LocalAppController.current
    val container = app.container
    val database = container.database
    val blasterState by container.blaster.state.collectAsStateWithLifecycle()
    val candidates = remember { database.search.value.results.take(MAX_CANDIDATES) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<RemoteImport?>(null) }
    var finished by remember { mutableStateOf(false) }
    var autoAdvance by rememberSaveable { mutableStateOf(false) }
    var interval by rememberSaveable { mutableFloatStateOf(3f) }

    fun step(delta: Int) {
        index = (index + delta).coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        round++
    }

    // Each step (or "send again") downloads the remote and sends its power code.
    LaunchedEffect(index, round) {
        val entry = candidates.getOrNull(index) ?: return@LaunchedEffect
        current = null
        finished = false
        status = "Downloading…"
        val result = database.loadPreview(entry)
        if (result == null) {
            status = "Couldn’t download this remote."
        } else {
            current = result
            val power = result.remote.powerButton
            if (power == null) {
                status = "This remote has no power button."
            } else {
                status = "Sending “${power.displayName}”…"
                status = try {
                    container.blaster.send(power.signal, power.name, container.settings.settings.value.extraRepeats)
                    "Sent “${power.displayName}”. Did your device respond?"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
        }
        finished = true
    }
    // After each attempt (or when turned on), move to the next remote after the chosen pause.
    LaunchedEffect(autoAdvance, finished) {
        if (autoAdvance && finished && index < candidates.size - 1) {
            delay((interval * 1000).toLong())
            step(1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Power scan") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
            )
        },
    ) { padding ->
        if (candidates.isEmpty()) {
            EmptyState(
                Icons.Rounded.SearchOff,
                "No remotes to scan",
                "Search or filter the database first, for example by brand and device type.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        Box(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = RemoteColors.power, modifier = Modifier.size(48.dp))
                Text(
                    "Point the blaster at your device. The app sends the power code from each matching remote — stop when your device turns on or off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                LinearWavyProgressIndicator(
                    progress = { (index + 1f) / candidates.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Remote ${index + 1} of ${candidates.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(candidates[index].title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { step(-1) },
                        enabled = index > 0,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Rounded.SkipPrevious, "Previous remote") }
                    FilledIconButton(
                        onClick = { round++ },
                        modifier = Modifier.size(80.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RemoteColors.power),
                    ) { Icon(Icons.Rounded.PowerSettingsNew, "Send again", Modifier.size(36.dp)) }
                    FilledTonalIconButton(
                        onClick = { step(1) },
                        enabled = index < candidates.size - 1,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Rounded.SkipNext, "Next remote") }
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Advance automatically", modifier = Modifier.weight(1f))
                        Switch(checked = autoAdvance, onCheckedChange = { autoAdvance = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every ${String.format(Locale.ROOT, "%.1f", interval)} s", modifier = Modifier.width(96.dp))
                        val sliderState = remember { SliderState(interval, steps = 17, trackRange = 1f..10f) }
                        Slider(
                            state = sliderState,
                            onValueChange = {
                                sliderState.value = it
                                interval = it
                            },
                            enabled = autoAdvance,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (blasterState !is BlasterManager.State.Ready) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SensorsOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect your IR blaster to scan.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        current?.let {
                            autoAdvance = false
                            val added = container.library.add(it.remote)
                            onDone()
                            app.openRemote(added.id)
                        }
                    },
                    enabled = current != null,
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text("It worked — add this remote")
                }
            }
        }
    }
}
