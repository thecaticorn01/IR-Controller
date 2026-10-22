package com.thecaticorn01.ircontroller.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.UiMessage
import com.thecaticorn01.ircontroller.data.DatabaseRepository
import com.thecaticorn01.ircontroller.data.DatabaseRepository.LoadState
import com.thecaticorn01.ircontroller.data.SecretStore
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.SectionHeader
import com.thecaticorn01.ircontroller.core.database.DatabaseFormat
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manage online database sources and the optional GitHub token. */
@Composable
fun DatabaseSettingsScreen(onBack: () -> Unit) {
    val app = LocalAppController.current
    val container = app.container
    val database = container.database
    val sources by database.sources.collectAsStateWithLifecycle()
    val states by database.loadStates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(container.secrets.read(SecretStore.GITHUB_TOKEN) ?: "") }
    var showToken by remember { mutableStateOf(false) }
    val isLoading = states.values.any { it is LoadState.Loading }
    // Keeps "Updated … ago" current while the screen is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Databases") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                item { SectionHeader("Sources") }
                items(sources, key = { it.id }) { source ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        supportingContent = {
                            Column {
                                Text(source.location, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(status(source, states[source.id], now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                if (source.summary.isNotEmpty()) Text(source.summary, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!source.isBuiltIn) {
                                    IconButton(onClick = { database.removeSource(source.id) }) {
                                        Icon(Icons.Rounded.DeleteOutline, "Remove ${source.name}")
                                    }
                                }
                                Switch(checked = source.isEnabled, onCheckedChange = { database.setEnabled(it, source.id) })
                            }
                        },
                    ) { Text(source.name) }
                }
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { showAdd = true }) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add GitHub repository")
                        }
                        FilledTonalButton(onClick = { scope.launch { database.refreshAll() } }, enabled = !isLoading) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isLoading) "Refreshing…" else "Refresh all")
                        }
                        OutlinedButton(onClick = {
                            database.clearCache()
                            container.show(UiMessage("Cached indexes cleared"))
                        }) { Text("Clear cache") }
                    }
                }
                item {
                    Footer(
                        "Indexes are cached on your device and refreshed every few days. Remote files are downloaded only when you open them.",
                    )
                }

                item { SectionHeader("GitHub") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Personal access token (optional)") },
                            leadingIcon = { Icon(Icons.Rounded.Key, null) },
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showToken) "Hide token" else "Show token")
                                }
                            },
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                container.secrets.write(SecretStore.GITHUB_TOKEN, token.trim().ifEmpty { null })
                                container.show(UiMessage(if (token.isBlank()) "Token removed" else "Token saved"))
                            }) { Text("Save token") }
                        }
                        Text(
                            "Indexes are fetched with GitHub’s public API, which allows 60 requests an hour without a token; " +
                                "the app falls back to the jsDelivr mirror when the limit is reached. A token with no scopes raises the limit. " +
                                "It is encrypted with a key kept in Android’s keystore.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showAdd) AddSourceDialog(onDismiss = { showAdd = false })
}

private fun status(source: DatabaseSource, state: LoadState?, now: Long): String = when (state) {
    null -> if (source.isEnabled) "Not downloaded yet" else "Off"
    LoadState.Loading -> "Downloading index…"
    is LoadState.Loaded -> "Updated " + updatedAgo(state.fetchedAtMillis, now)
    is LoadState.Failed -> "Failed: ${state.message}"
}

/** "just now" for the first minute, where DateUtils would say "0 minutes ago"; then "5 minutes ago", "yesterday", … */
internal fun updatedAgo(fetchedAtMillis: Long, now: Long): CharSequence =
    if (now - fetchedAtMillis < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(fetchedAtMillis, now, DateUtils.MINUTE_IN_MILLIS)

/** Adds any GitHub repository of Flipper `.ir` or IRDB-style `.csv` files. */
@Composable
private fun AddSourceDialog(onDismiss: () -> Unit) {
    val app = LocalAppController.current
    var repository by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("main") }
    var folder by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(DatabaseFormat.FLIPPER) }
    val parsed = DatabaseSource.parseRepository(repository)
    val sources by app.container.database.sources.collectAsStateWithLifecycle()
    val existingIds = sources.map { it.id }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add database source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Any public GitHub repository containing Flipper Zero .ir files (for example a Flipper-IRDB fork) or IRDB-style CSV files.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val suggestions = DatabaseRepository.suggestedSources.filter { it.id !in existingIds }
                if (suggestions.isNotEmpty()) {
                    Text("Suggestions", style = MaterialTheme.typography.labelLarge)
                    for (suggestion in suggestions) {
                        SuggestionChip(
                            onClick = {
                                repository = "${suggestion.owner}/${suggestion.repository}"
                                name = suggestion.name
                                branch = suggestion.branch
                                folder = suggestion.subpath
                                format = suggestion.format
                            },
                            label = { Text(suggestion.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = repository, onValueChange = { repository = it },
                    label = { Text("Repository") }, placeholder = { Text("owner/repo or GitHub URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = repository.isNotBlank() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Display name") }, placeholder = { Text(parsed?.second ?: "My IR files") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = branch, onValueChange = { branch = it }, label = { Text("Branch") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = folder, onValueChange = { folder = it }, label = { Text("Folder") },
                        placeholder = { Text("Optional") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DatabaseFormat.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = format == option,
                            onClick = { format = option },
                            shape = SegmentedButtonDefaults.itemShape(index, DatabaseFormat.entries.size),
                        ) { Text(option.displayName) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    val (owner, repo) = parsed ?: return@TextButton
                    val source = DatabaseSource.make(
                        name = name.ifBlank { repo }, owner = owner, repository = repo,
                        branch = branch.trim().ifEmpty { "main" }, subpath = folder.trim(), format = format,
                    )
                    if (app.container.database.addSource(source)) {
                        app.container.show(UiMessage("Added ${source.name}. Downloading its index…"))
                    } else {
                        app.container.show(UiMessage("That source is already in your list"))
                    }
                    onDismiss()
                },
            ) { Text("Add source") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
