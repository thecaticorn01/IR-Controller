package com.thecaticorn01.ircontroller.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.data.DatabaseRepository.PreviewState
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.BlasterStatusButton
import com.thecaticorn01.ircontroller.ui.components.ButtonGrid
import com.thecaticorn01.ircontroller.ui.components.CategoryBadge
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.components.LocalRemoteActions
import com.thecaticorn01.ircontroller.core.database.DatabaseEntry
import com.thecaticorn01.ircontroller.core.database.RemoteImport
import com.thecaticorn01.ircontroller.core.model.searchKey
import kotlinx.coroutines.launch

/** Downloads a database remote, lets the user try its buttons, and imports it. */
@Composable
fun PreviewScreen(entryId: String, onBack: (() -> Unit)?) {
    val app = LocalAppController.current
    val database = app.container.database
    val previews by database.previews.collectAsStateWithLifecycle()
    val allEntries by database.allEntries.collectAsStateWithLifecycle()
    val entry = allEntries.firstOrNull { it.id == entryId } ?: database.entry(entryId)
    val scope = rememberCoroutineScope()

    LaunchedEffect(entryId, entry != null) { entry?.let { database.loadPreview(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: "Remote", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = { BlasterStatusButton() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entry == null) {
                EmptyState(Icons.Rounded.SettingsRemote, "Remote not found", "The database index changed. Search again to find it.")
                return@Box
            }
            when (val state = previews[entry.id]) {
                is PreviewState.Loaded -> Loaded(entry, state.result)
                is PreviewState.Failed -> EmptyState(Icons.Rounded.CloudOff, "Couldn’t load remote", state.message) {
                    Button(onClick = { scope.launch { database.loadPreview(entry, force = true) } }) { Text("Try again") }
                }
                else -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LoadingIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text("Downloading ${entry.title}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Loaded(entry: DatabaseEntry, result: RemoteImport) {
    val app = LocalAppController.current
    val actions = LocalRemoteActions.current
    val remotes by app.container.library.remotes.collectAsStateWithLifecycle()
    val existing = remotes.firstOrNull { it.origin?.path == entry.path && it.origin?.sourceId == entry.sourceId }
    val source = app.container.database.source(entry.sourceId)
    var filter by rememberSaveable(entry.id) { mutableStateOf("") }
    val remote = result.remote
    val key = filter.searchKey
    val buttons = if (key.isEmpty()) remote.buttons else remote.buttons.filter { it.name.searchKey.contains(key) }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CategoryBadge(entry.category, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.brand.ifEmpty { entry.model }, style = MaterialTheme.typography.headlineSmall)
                    if (entry.brand.isNotEmpty()) {
                        Text(entry.model, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        listOfNotNull(entry.category.displayName, "${remote.buttons.size} buttons", source?.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (result.warnings.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (warning in result.warnings) {
                            Row {
                                Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    Button(
                        onClick = { app.openRemote(existing.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B)),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open in My Remotes")
                    }
                } else {
                    Button(onClick = {
                        val added = app.container.library.add(remote)
                        app.openRemote(added.id)
                    }) {
                        Icon(Icons.Rounded.AddCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add to My Remotes")
                    }
                }
                remote.powerButton?.let { power ->
                    FilledTonalButton(onClick = { actions.send(power) }) {
                        Icon(Icons.Rounded.PowerSettingsNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test power")
                    }
                }
                source?.let {
                    TextButton(onClick = { app.openUrl(it.webUrl(entry.path)) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("View on GitHub")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Try the buttons", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap any button to send it with your blaster before adding the remote.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (remote.buttons.size > 12) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter buttons") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ButtonGrid(buttons, remote = null, minCellWidth = 140.dp)
        }
    }
}
