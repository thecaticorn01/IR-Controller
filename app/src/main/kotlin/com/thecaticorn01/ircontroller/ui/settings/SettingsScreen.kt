package com.thecaticorn01.ircontroller.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.BuildConfig
import com.thecaticorn01.ircontroller.data.ThemeMode
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.BlasterDetails
import com.thecaticorn01.ircontroller.ui.components.SectionHeader
import java.text.NumberFormat

private val frequencies = listOf(
    36_000 to "36 kHz (Philips RC5/RC6)",
    38_000 to "38 kHz (most remotes)",
    40_000 to "40 kHz (Sony)",
    56_000 to "56 kHz (RCA)",
)

@Composable
fun SettingsScreen(onOpenDatabases: () -> Unit) {
    val app = LocalAppController.current
    val container = app.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val sources by container.database.sources.collectAsStateWithLifecycle()
    val entries by container.database.allEntries.collectAsStateWithLifecycle()
    val remotes by container.library.remotes.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val repo = container.settings

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("Settings") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                item { SectionHeader("IR blaster") }
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        BlasterDetails(Modifier.padding(16.dp))
                    }
                }

                item { SectionHeader("Learning") }
                item {
                    SliderSetting(
                        icon = Icons.Rounded.Timer,
                        title = "Listen for up to",
                        valueText = { "${it.toInt()} seconds" },
                        value = settings.learnTimeoutSeconds.toFloat(),
                        range = 5f..60f,
                        steps = 10,
                        onChange = { repo.setLearnTimeout(it.toInt()) },
                    )
                }
                item {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        SettingItem(
                            icon = Icons.Rounded.Waves,
                            title = "Carrier frequency for learned codes",
                            subtitle = frequencies.firstOrNull { it.first == settings.learnFrequency }?.second ?: "${settings.learnFrequency / 1000} kHz",
                            onClick = { open = true },
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for ((hertz, label) in frequencies) {
                                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; repo.setLearnFrequency(hertz) })
                            }
                        }
                    }
                }
                item {
                    SwitchSetting(
                        icon = Icons.Rounded.Tune,
                        title = "Convert recognised codes",
                        subtitle = "Store learned NEC, Samsung, Sony and RC5 codes as clean protocol codes instead of raw recordings.",
                        checked = settings.preferDecoded,
                        onChange = repo::setPreferDecoded,
                    )
                }
                item {
                    Footer("The blaster’s receiver records timing but not the carrier frequency. 38 kHz works for the vast majority of devices.")
                }

                item { SectionHeader("Sending") }
                item {
                    SliderSetting(
                        icon = Icons.Rounded.Repeat,
                        title = "Extra repeats per press",
                        valueText = { "${it.toInt()}" },
                        value = settings.extraRepeats.toFloat(),
                        range = 0f..10f,
                        steps = 9,
                        onChange = { repo.setExtraRepeats(it.toInt()) },
                    )
                }
                item { Footer("Some devices need a code repeated before they respond. Increase this if buttons work only intermittently.") }
                item {
                    SwitchSetting(Icons.Rounded.Vibration, "Haptic feedback", "Vibrate briefly when you press a button.", settings.haptics, repo::setHaptics)
                }
                item {
                    SwitchSetting(
                        Icons.AutoMirrored.Rounded.VolumeUp,
                        "Volume keys control the remote",
                        "While a remote is open, your phone’s volume keys press its volume buttons.",
                        settings.volumeKeys,
                        repo::setVolumeKeys,
                    )
                }
                item {
                    SwitchSetting(
                        Icons.Rounded.Keyboard,
                        "Control remotes with a keyboard",
                        "Arrow keys move, Enter selects, Space plays/pauses, + and − change volume, M mutes, P toggles power, [ and ] change channel and digits enter numbers.",
                        settings.keyboardShortcuts,
                        repo::setKeyboardShortcuts,
                    )
                }

                item { SectionHeader("Appearance") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Contrast, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.themeMode == mode,
                                    onClick = { repo.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                ) { Text(mode.displayName.removeSuffix(" default")) }
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    item {
                        SwitchSetting(
                            Icons.Rounded.Palette,
                            "Dynamic colour",
                            "Use colours from your wallpaper.",
                            settings.dynamicColor,
                            repo::setDynamicColor,
                        )
                    }
                }

                item { SectionHeader("Databases") }
                item {
                    SettingItem(
                        icon = Icons.Rounded.Dns,
                        title = "Remote databases",
                        subtitle = "${sources.count { it.isEnabled }} of ${sources.size} sources on · ${NumberFormat.getIntegerInstance().format(entries.size)} remotes indexed",
                        onClick = onOpenDatabases,
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    )
                }

                item { SectionHeader("Home screen & automation") }
                item {
                    var open by remember { mutableStateOf(false) }
                    val widgetRemote = remotes.firstOrNull { it.id == settings.widgetRemoteId }
                    Box {
                        SettingItem(
                            icon = Icons.Rounded.Widgets,
                            title = "Quick Remote widget",
                            subtitle = widgetRemote?.name ?: "First favourite remote",
                            onClick = { open = true },
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("First favourite remote") }, onClick = { open = false; repo.setWidgetRemoteId(null) })
                            for (remote in remotes) {
                                DropdownMenuItem(text = { Text(remote.name) }, onClick = { open = false; repo.setWidgetRemoteId(remote.id) })
                            }
                        }
                    }
                }
                item {
                    Footer("Add the Quick Remote widget from your home screen’s widget picker. Long-press any button in a remote to pin it to your home screen.")
                }
                item {
                    SwitchSetting(
                        Icons.Rounded.SmartToy,
                        "Allow automation apps",
                        "Let apps such as Tasker or Home Assistant press buttons on your remotes.",
                        settings.allowAutomation,
                        repo::setAllowAutomation,
                    )
                }
                if (settings.allowAutomation) {
                    item {
                        val example = "am broadcast -a ${com.thecaticorn01.ircontroller.system.ShortcutPublisher.ACTION_PRESS_BUTTON} " +
                            "-n ${BuildConfig.APPLICATION_ID}/.system.AutomationReceiver --es remote \"Living Room TV\" --es button \"Power\""
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                "Send a broadcast with the action below and the extras remote, button and (optionally) repeat:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                                modifier = Modifier.padding(vertical = 8.dp).clickable { app.copyText("Automation command", example, "Copied example command") },
                            ) {
                                Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }

                item { SectionHeader("Library") }
                item {
                    SettingItem(Icons.Rounded.FileOpen, "Import files", "Flipper .ir, IRDB .csv or a library backup (.json)", onClick = app::importFiles)
                }
                item {
                    SettingItem(
                        Icons.Rounded.Backup,
                        "Back up library",
                        "Save all remotes as a .json file you can import again later or on another device.",
                        onClick = app::exportLibrary,
                    )
                }

                item { SectionHeader("About") }
                item {
                    SettingItem(
                        Icons.Rounded.Info,
                        "IR Controller ${BuildConfig.VERSION_NAME}",
                        "Remote data is downloaded at runtime from Flipper-IRDB and IRDB; see those projects for their licences. " +
                            "ElkSmart protocol documentation: elksmart-comm by Přemysl Eric Janouch.",
                    )
                }
                item {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { app.openUrl("https://github.com/Lucaslhm/Flipper-IRDB") }) { Text("Flipper-IRDB") }
                        TextButton(onClick = { app.openUrl("https://github.com/probonopd/irdb") }) { Text("IRDB") }
                        TextButton(onClick = { app.openUrl("https://git.janouch.name/p/usb-drivers") }) { Text("elksmart-comm") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            leadingContent = { Icon(icon, null) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = trailing,
            colors = colors,
        ) { Text(title) }
    } else {
        ListItem(
            leadingContent = { Icon(icon, null) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = trailing,
            colors = colors,
        ) { Text(title) }
    }
}

@Composable
fun SwitchSetting(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingItem(icon, title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    icon: ImageVector,
    title: String,
    valueText: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    // Track the thumb locally so dragging is smooth; persist when released.
    val state = remember(steps, range) { SliderState(value, steps, range) }
    LaunchedEffect(value) { state.value = value }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
            Text(valueText(state.value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            state = state,
            onValueChange = { state.value = it },
            onValueChangeFinished = { onChange(state.value) },
            modifier = Modifier.padding(start = 40.dp),
        )
    }
}

@Composable
fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
    )
}
