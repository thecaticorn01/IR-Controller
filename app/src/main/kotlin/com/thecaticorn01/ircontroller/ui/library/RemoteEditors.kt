package com.thecaticorn01.ircontroller.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.RemoteInfoTarget
import com.thecaticorn01.ircontroller.ui.components.CategoryBadge
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.components.LocalRemoteActions
import com.thecaticorn01.ircontroller.ui.components.ManualCodeEditor
import com.thecaticorn01.ircontroller.ui.components.SignalCapture
import com.thecaticorn01.ircontroller.ui.components.SignalSummary
import com.thecaticorn01.ircontroller.ui.theme.icon
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.LearningTemplate
import com.thecaticorn01.ircontroller.core.model.searchKey

/** Device-type picker shared by the remote details dialog and the learning wizard. */
@Composable
fun CategoryPicker(category: DeviceCategory, onChange: (DeviceCategory) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = category.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Device type") },
            leadingIcon = { Icon(category.icon, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in DeviceCategory.entries) {
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    leadingIcon = { Icon(option.icon, null) },
                    onClick = { onChange(option); expanded = false },
                )
            }
        }
    }
}

/** Creates a new empty remote or edits a remote's details. */
@Composable
fun RemoteInfoDialog(target: RemoteInfoTarget, onDismiss: () -> Unit) {
    val app = LocalAppController.current
    val existing = (target as? RemoteInfoTarget.Edit)?.let { app.container.library.remote(it.remoteId) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var brand by rememberSaveable { mutableStateOf(existing?.brand ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: DeviceCategory.TV) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { CategoryBadge(category, size = 48.dp) },
        title = { Text(if (existing == null) "New remote" else "Remote details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") },
                    placeholder = { Text("Living Room TV") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                CategoryPicker(category, { category = it })
                OutlinedTextField(
                    value = brand, onValueChange = { brand = it }, label = { Text("Brand (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, label = { Text("Model (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") },
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val library = app.container.library
                    if (existing != null) {
                        library.modify(existing.id) {
                            it.copy(name = name.trim(), brand = brand.trim(), model = model.trim(), category = category, notes = notes)
                        }
                    } else {
                        val added = library.add(
                            IRRemote(name = name.trim(), brand = brand.trim(), model = model.trim(), category = category, notes = notes),
                        )
                        app.openRemote(added.id)
                    }
                    onDismiss()
                },
            ) { Text(if (existing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adds a button to a remote (learned or entered) or edits an existing one. */
@Composable
fun ButtonEditorScreen(remoteId: String, buttonId: String?, startInLearnMode: Boolean, onDone: () -> Unit) {
    val app = LocalAppController.current
    val actions = LocalRemoteActions.current
    val remotes by app.container.library.remotes.collectAsStateWithLifecycle()
    val remote = remotes.firstOrNull { it.id == remoteId }
    val existing = buttonId?.let { id -> remote?.buttons?.firstOrNull { it.id == id } }
    if (remote == null || (buttonId != null && existing == null)) {
        EmptyState(Icons.Rounded.SettingsRemote, "Not found", "This remote or button no longer exists.") {
            TextButton(onClick = onDone) { Text("Go back") }
        }
        return
    }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var learnMode by rememberSaveable { mutableStateOf(startInLearnMode) }
    var learned by remember { mutableStateOf<IRSignal?>(null) }
    var entered by remember { mutableStateOf<IRSignal?>(null) }
    val signal = if (learnMode) learned else entered
    // When editing, renaming alone is allowed.
    val canSave = name.isNotBlank() && (signal != null || existing != null)
    val suggestions = remember(remote.buttons) {
        val taken = remote.buttons.map { it.name.searchKey }.toSet()
        LearningTemplate.buttonNames(remote.category).filter { it.searchKey !in taken }
    }

    fun save() {
        val trimmed = name.trim()
        if (existing != null) {
            app.container.library.updateButton(existing.copy(name = trimmed, signal = signal ?: existing.signal), remoteId)
        } else if (signal != null) {
            app.container.library.addButton(IRButton(name = trimmed, signal = signal), remoteId)
        }
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add button" else "Edit button") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Cancel") } },
                actions = {
                    TextButton(onClick = ::save, enabled = canSave) { Text(if (existing == null) "Add" else "Save") }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(remote.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Button name") },
                    placeholder = { Text("e.g. Power") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (existing == null && suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions) { suggestion ->
                            SuggestionChip(onClick = { name = suggestion }, label = { Text(suggestion) })
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = learnMode,
                        onClick = { learnMode = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Rounded.WifiTethering, null) },
                    ) { Text("Learn from remote") }
                    SegmentedButton(
                        selected = !learnMode,
                        onClick = { learnMode = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Rounded.Numbers, null) },
                    ) { Text("Enter code") }
                }
                if (learnMode) {
                    if (existing != null && learned == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Current code", style = MaterialTheme.typography.titleSmall)
                            SignalSummary(existing.signal)
                        }
                    }
                    SignalCapture(
                        buttonName = name.ifBlank { "the button" },
                        onSignal = { learned = it },
                        autoStart = existing == null,
                    )
                } else {
                    ManualCodeEditor(initial = existing?.signal, onSignal = { entered = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (signal != null) {
                        FilledTonalButton(onClick = { actions.send(IRButton(name = name.ifBlank { "Test" }, signal = signal)) }) {
                            Icon(Icons.Rounded.SettingsRemote, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Test")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = ::save, enabled = canSave) { Text(if (existing == null) "Add button" else "Save") }
                }
            }
        }
    }
}
