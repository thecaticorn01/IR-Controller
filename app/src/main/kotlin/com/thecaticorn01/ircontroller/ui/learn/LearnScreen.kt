package com.thecaticorn01.ircontroller.ui.learn

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thecaticorn01.ircontroller.AppContainer
import com.thecaticorn01.ircontroller.blaster.BlasterManager
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.BlasterStatusButton
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.components.ManualCodeEditor
import com.thecaticorn01.ircontroller.ui.components.SignalCapture
import com.thecaticorn01.ircontroller.ui.components.SignalSummary
import com.thecaticorn01.ircontroller.ui.library.CategoryPicker
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.LearningTemplate
import com.thecaticorn01.ircontroller.core.model.newID

/** One button the wizard asks for. */
data class Slot(val id: String = newID(), val name: String, val signal: IRSignal? = null, val skipped: Boolean = false)

/** State of the learning wizard; lives as long as the Learn tab. */
class LearnViewModel(private val container: AppContainer) : ViewModel() {
    var learning by mutableStateOf(false); private set
    var name by mutableStateOf("")
    var brand by mutableStateOf("")
    var model by mutableStateOf("")
    var category by mutableStateOf(DeviceCategory.TV)
    var slots by mutableStateOf(listOf<Slot>()); private set
    var currentSlotId by mutableStateOf<String?>(null); private set
    var captured by mutableStateOf<IRSignal?>(null)
    var entered by mutableStateOf<IRSignal?>(null)
    var entering by mutableStateOf(false)

    val learnedCount: Int get() = slots.count { it.signal != null }

    fun begin() {
        slots = LearningTemplate.buttonNames(category).map { Slot(name = it) }
        select(slots.firstOrNull()?.id)
        learning = true
    }

    fun select(id: String?) {
        currentSlotId = id
        captured = null
        entered = null
        entering = false
    }

    fun addSlot(slotName: String) {
        val trimmed = slotName.trim()
        if (trimmed.isEmpty()) return
        val slot = Slot(name = trimmed)
        slots = slots + slot
        select(slot.id)
    }

    fun removeSlot(id: String) {
        slots = slots.filterNot { it.id == id }
        if (currentSlotId == id) advance(null)
    }

    fun skipCurrent() {
        val id = currentSlotId ?: return
        slots = slots.map { if (it.id == id) it.copy(skipped = true) else it }
        advance(id)
    }

    fun saveCurrent() {
        val id = currentSlotId ?: return
        val signal = (if (entering) entered else captured) ?: return
        slots = slots.map { if (it.id == id) it.copy(signal = signal, skipped = false) else it }
        advance(id)
    }

    /** Moves to the next button that still needs a code, wrapping around. */
    private fun advance(fromId: String?) {
        val index = slots.indexOfFirst { it.id == fromId }
        val pending = { slot: Slot -> slot.signal == null && !slot.skipped }
        val next = slots.drop(index + 1).firstOrNull(pending) ?: slots.firstOrNull(pending)
        select(next?.id)
    }

    /** Saves the learned buttons as a new remote and returns it. */
    fun finish(): IRRemote? {
        val buttons = slots.mapNotNull { slot -> slot.signal?.let { IRButton(name = slot.name, signal = it) } }
        if (buttons.isEmpty()) return null
        val remote = container.library.add(
            IRRemote(name = name.trim(), brand = brand.trim(), model = model.trim(), category = category, buttons = buttons),
        )
        reset()
        return remote
    }

    fun reset() {
        learning = false
        slots = emptyList()
        select(null)
        name = ""
        brand = ""
        model = ""
    }
}

/**
 * Guided flow for teaching the app a remote that isn't in any database:
 * describe the device, then capture each button from the physical remote.
 */
@Composable
fun LearnScreen() {
    val app = LocalAppController.current
    val vm = viewModel { LearnViewModel(app.container) }
    var confirmDiscard by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (vm.learning) {
                        Column {
                            Text(vm.name)
                            Text(
                                "${vm.learnedCount} of ${vm.slots.size} learned",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text("Learn a new remote")
                    }
                },
                navigationIcon = {
                    if (vm.learning) {
                        IconButton(onClick = { if (vm.learnedCount > 0) confirmDiscard = true else vm.reset() }) {
                            Icon(Icons.Rounded.Close, "Start over")
                        }
                    }
                },
                actions = {
                    BlasterStatusButton()
                    if (vm.learning) {
                        TextButton(onClick = { vm.finish()?.let { app.openRemote(it.id) } }, enabled = vm.learnedCount > 0) {
                            Text("Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            AnimatedContent(vm.learning, label = "learn-step") { learning ->
                if (learning) Learning(vm) else Setup(vm)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard the buttons you’ve learned?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.reset() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep learning") } },
        )
    }
}

@Composable
private fun Setup(vm: LearnViewModel) {
    val blaster = LocalAppController.current.container.blaster
    val state by blaster.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.WifiTethering, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(
                "Can’t find your remote in the databases? Teach the app its buttons one by one using your ELK Smart blaster’s receiver.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Bedroom Fan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            CategoryPicker(vm.category, { vm.category = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vm.brand, onValueChange = { vm.brand = it }, label = { Text("Brand (optional)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    value = vm.model, onValueChange = { vm.model = it }, label = { Text("Model (optional)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Suggested buttons", style = MaterialTheme.typography.titleSmall)
                Text(
                    "You can skip any of these and add your own as you go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (name in LearningTemplate.buttonNames(vm.category)) {
                        SuggestionChip(onClick = {}, label = { Text(name) })
                    }
                }
            }
            if (state !is BlasterManager.State.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SensorsOff, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Connect your IR blaster to capture codes. You can still enter codes manually.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Button(onClick = vm::begin, enabled = vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Start learning")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun Learning(vm: LearnViewModel) {
    val app = LocalAppController.current
    if (app.isExpanded) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                SlotList(vm, Modifier.padding(16.dp))
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                CaptureArea(vm, Modifier.widthIn(max = 640.dp).padding(24.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LinearWavyProgressIndicator(
                progress = { if (vm.slots.isEmpty()) 0f else vm.learnedCount.toFloat() / vm.slots.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            CaptureArea(vm, Modifier.padding(16.dp))
            SlotList(vm, Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun CaptureArea(vm: LearnViewModel, modifier: Modifier = Modifier) {
    val app = LocalAppController.current
    val state by app.container.blaster.state.collectAsStateWithLifecycle()
    val index = vm.slots.indexOfFirst { it.id == vm.currentSlotId }
    if (index < 0) {
        Box(modifier.fillMaxWidth()) {
            EmptyState(
                if (vm.learnedCount > 0) Icons.Rounded.TaskAlt else Icons.Rounded.RadioButtonUnchecked,
                if (vm.learnedCount > 0) "All done" else "Pick a button",
                if (vm.learnedCount > 0) {
                    "You learned ${vm.learnedCount} buttons. Save the remote, or pick a button to redo it."
                } else {
                    "Choose a button below to start learning."
                },
            ) {
                if (vm.learnedCount > 0) {
                    Button(onClick = { vm.finish()?.let { app.openRemote(it.id) } }) {
                        Icon(Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save remote")
                    }
                }
            }
        }
        return
    }
    val slot = vm.slots[index]
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Button ${index + 1} of ${vm.slots.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(slot.name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        val existing = slot.signal
        if (existing != null && vm.captured == null && !vm.entering) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF2E9E5B))
                        Spacer(Modifier.width(8.dp))
                        Text("Already learned", style = MaterialTheme.typography.titleSmall)
                    }
                    SignalSummary(existing)
                }
            }
        }
        if (vm.entering) {
            Card(Modifier.fillMaxWidth()) {
                ManualCodeEditor(initial = null, onSignal = { vm.entered = it }, modifier = Modifier.padding(16.dp))
            }
        } else {
            SignalCapture(
                buttonName = slot.name,
                onSignal = { vm.captured = it },
                autoStart = slot.signal == null && state is BlasterManager.State.Ready,
                sessionKey = slot.id,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.entering = !vm.entering }) {
                Icon(if (vm.entering) Icons.Rounded.WifiTethering else Icons.Rounded.Numbers, null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.entering) "Learn instead" else "Enter code")
            }
            FilledTonalButton(onClick = vm::skipCurrent) {
                Icon(Icons.Rounded.FastForward, null)
                Spacer(Modifier.width(8.dp))
                Text("Skip")
            }
            Button(onClick = vm::saveCurrent, enabled = (if (vm.entering) vm.entered else vm.captured) != null) {
                Text("Save & next")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun SlotList(vm: LearnViewModel, modifier: Modifier = Modifier) {
    var newSlot by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Buttons", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in vm.slots) {
                val selected = slot.id == vm.currentSlotId
                InputChip(
                    selected = selected,
                    onClick = { vm.select(slot.id) },
                    label = { Text(slot.name) },
                    leadingIcon = {
                        Icon(
                            when {
                                slot.signal != null -> Icons.Rounded.CheckCircle
                                slot.skipped -> Icons.Rounded.FastForward
                                selected -> Icons.Rounded.RadioButtonChecked
                                else -> Icons.Rounded.RadioButtonUnchecked
                            },
                            null,
                            tint = when {
                                slot.signal != null -> Color(0xFF2E9E5B)
                                slot.skipped -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { vm.removeSlot(slot.id) }, modifier = Modifier.size(InputChipDefaults.IconSize)) {
                            Icon(Icons.Rounded.Close, "Remove ${slot.name}", Modifier.size(InputChipDefaults.IconSize))
                        }
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newSlot,
                onValueChange = { newSlot = it },
                placeholder = { Text("Add a button…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.addSlot(newSlot); newSlot = "" }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { vm.addSlot(newSlot); newSlot = "" }, enabled = newSlot.isNotBlank()) {
                Icon(Icons.Rounded.Add, "Add button")
            }
        }
        Text(
            "Tip: hold the remote about 1 cm from the end of the blaster. Undo a mistake by picking the button again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
