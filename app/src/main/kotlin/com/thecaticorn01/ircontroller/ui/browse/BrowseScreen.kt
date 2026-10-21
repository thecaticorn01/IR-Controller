package com.thecaticorn01.ircontroller.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.data.DatabaseRepository
import com.thecaticorn01.ircontroller.data.DatabaseRepository.LoadState
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.PowerScanRoute
import com.thecaticorn01.ircontroller.ui.components.BlasterStatusButton
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.theme.icon
import com.thecaticorn01.ircontroller.core.database.DatabaseEntry
import com.thecaticorn01.ircontroller.core.database.DatabaseQuery
import com.thecaticorn01.ircontroller.core.database.FacetCount
import com.thecaticorn01.ircontroller.core.model.searchKey
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat

private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance()

/** Search and filter remotes across online databases, preview them, and import. */
@Composable
fun BrowseScreen() {
    val app = LocalAppController.current
    val database = app.container.database
    val selected by database.selectedEntryId.collectAsStateWithLifecycle()
    val search by database.search.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { database.refreshIfNeeded() }
    if (app.isExpanded) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(420.dp).fillMaxHeight()) { BrowseList(selectedId = selected) }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val entry = selected?.let { id -> search.results.firstOrNull { it.id == id } ?: database.entry(id) }
                if (entry != null) {
                    PreviewScreen(entryId = entry.id, onBack = null)
                } else {
                    EmptyState(
                        Icons.Rounded.SettingsRemote,
                        "Select a remote",
                        "Pick a result to preview its buttons. You can try them with your blaster before adding the remote.",
                    )
                }
            }
        }
    } else {
        BrowseList(selectedId = null)
    }
}

@Composable
private fun BrowseList(selectedId: String?) {
    val app = LocalAppController.current
    val database = app.container.database
    val query by database.query.collectAsStateWithLifecycle()
    val search by database.search.collectAsStateWithLifecycle()
    val entries by database.allEntries.collectAsStateWithLifecycle()
    val loadStates by database.loadStates.collectAsStateWithLifecycle()
    val sources by database.sources.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isLoading = loadStates.values.any { it is LoadState.Loading }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Browse databases")
                        Text(subtitle(search, entries.size, isLoading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { app.navController.navigate(PowerScanRoute) }, enabled = search.results.isNotEmpty()) {
                        Icon(Icons.Rounded.PowerSettingsNew, "Power scan")
                    }
                    IconButton(onClick = { scope.launch { database.refreshAll() } }, enabled = !isLoading) {
                        Icon(Icons.Rounded.Refresh, "Refresh databases")
                    }
                    BlasterStatusButton()
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            OutlinedTextField(
                value = query.text,
                onValueChange = { text -> database.query.update { it.copy(text = text) } },
                placeholder = { Text("Brand, model or device") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.text.isNotEmpty()) {
                        IconButton(onClick = { database.query.update { it.copy(text = "") } }) { Icon(Icons.Rounded.Clear, "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FilterBar(query, search, sources.filter { it.isEnabled }.map { it.id to it.name })
            if (search.isSearching || isLoading) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            } else {
                Spacer(Modifier.height(4.dp))
            }
            when {
                entries.isEmpty() -> SourcesStatus()
                search.results.isEmpty() && !search.isSearching -> EmptyState(
                    Icons.Rounded.SearchOff,
                    "No results",
                    if (query.text.isBlank()) "No remotes match these filters." else "No remotes match “${query.text}”. Try fewer words or clear a filter.",
                )
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(search.results, key = { it.id }) { entry ->
                        ResultRow(
                            entry,
                            sourceName = database.source(entry.sourceId)?.name ?: "",
                            selected = entry.id == selectedId,
                        ) {
                            focusManager.clearFocus()
                            app.openPreview(entry.id)
                        }
                    }
                }
            }
        }
    }
}

private fun subtitle(search: DatabaseRepository.SearchState, total: Int, isLoading: Boolean): String {
    if (isLoading && total == 0) return "Downloading indexes…"
    val count = search.results.size
    val suffix = if (count >= DatabaseRepository.RESULT_LIMIT) "+" else ""
    return "${numberFormat.format(count)}$suffix of ${numberFormat.format(total)} remotes"
}

/** Device type, brand, source and curation filters. */
@Composable
private fun FilterBar(query: DatabaseQuery, search: DatabaseRepository.SearchState, sources: List<Pair<String, String>>) {
    val database = LocalAppController.current.container.database
    var categoryMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var brandSheet by rememberSaveable { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = query.category != null,
                    onClick = { categoryMenu = true },
                    label = { Text(query.category?.displayName ?: "Device type") },
                    leadingIcon = { Icon(query.category?.icon ?: Icons.Rounded.GridView, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
                )
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("All device types") },
                        leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                        trailingIcon = { if (query.category == null) Icon(Icons.Rounded.Check, null) },
                        onClick = { categoryMenu = false; database.query.update { it.copy(category = null) } },
                    )
                    HorizontalDivider()
                    for (facet in search.categoryFacets) {
                        DropdownMenuItem(
                            text = { Text("${facet.value.displayName} (${numberFormat.format(facet.count)})") },
                            leadingIcon = { Icon(facet.value.icon, null) },
                            trailingIcon = { if (query.category == facet.value) Icon(Icons.Rounded.Check, null) },
                            onClick = { categoryMenu = false; database.query.update { it.copy(category = facet.value) } },
                        )
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = query.brand != null,
                onClick = { brandSheet = true },
                label = { Text(query.brand ?: "Brand") },
                leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
            )
        }
        if (sources.size > 1) {
            item {
                Box {
                    val selectedSource = query.sourceIds.firstOrNull()
                    FilterChip(
                        selected = selectedSource != null,
                        onClick = { sourceMenu = true },
                        label = { Text(sources.firstOrNull { it.first == selectedSource }?.second ?: "Source") },
                        leadingIcon = { Icon(Icons.Rounded.Dns, null, Modifier.size(FilterChipDefaults.IconSize)) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
                    )
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("All sources") },
                            trailingIcon = { if (selectedSource == null) Icon(Icons.Rounded.Check, null) },
                            onClick = { sourceMenu = false; database.query.update { it.copy(sourceIds = emptySet()) } },
                        )
                        for ((id, name) in sources) {
                            DropdownMenuItem(
                                text = { Text(name) },
                                trailingIcon = { if (selectedSource == id) Icon(Icons.Rounded.Check, null) },
                                onClick = { sourceMenu = false; database.query.update { it.copy(sourceIds = setOf(id)) } },
                            )
                        }
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = query.curatedOnly,
                onClick = { database.query.update { it.copy(curatedOnly = !it.curatedOnly) } },
                label = { Text("Curated only") },
                leadingIcon = { Icon(Icons.Rounded.Verified, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        if (query.hasFilters) {
            item {
                InputChip(
                    selected = false,
                    onClick = { database.query.value = DatabaseQuery() },
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
    }
    if (brandSheet) {
        BrandPicker(
            selection = query.brand,
            facets = search.brandFacets,
            onPick = { brand ->
                database.query.update { it.copy(brand = brand) }
                brandSheet = false
            },
            onDismiss = { brandSheet = false },
        )
    }
}

/** Searchable brand list with match counts. */
@Composable
private fun BrandPicker(selection: String?, facets: List<FacetCount<String>>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(search, facets) {
        val key = search.searchKey
        if (key.isEmpty()) facets else facets.filter { it.value.searchKey.contains(key) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text("Brand", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search ${numberFormat.format(facets.size)} brands") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn {
                item {
                    ListItem(
                        onClick = { onPick(null) },
                        trailingContent = { if (selection == null) Icon(Icons.Rounded.Check, null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text("All brands") }
                }
                items(filtered, key = { it.value }) { facet ->
                    val isSelected = selection?.searchKey == facet.value.searchKey
                    ListItem(
                        onClick = { onPick(facet.value) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(numberFormat.format(facet.count), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (isSelected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.Check, null)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(facet.value, fontWeight = if (isSelected) FontWeight.SemiBold else null) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(entry: DatabaseEntry, sourceName: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        leadingContent = { Icon(entry.category.icon, null, tint = MaterialTheme.colorScheme.primary) },
        supportingContent = {
            Column {
                if (entry.brand.isNotEmpty()) Text(entry.model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(sourceName, entry.collection, entry.categoryName).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = if (entry.isCurated) {
            { Icon(Icons.Rounded.Verified, "Curated", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    ) {
        Text(entry.brand.ifEmpty { entry.model }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Download status of each source, shown until the first index arrives. */
@Composable
private fun SourcesStatus() {
    val database = LocalAppController.current.container.database
    val sources by database.sources.collectAsStateWithLifecycle()
    val states by database.loadStates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val failed = states.values.any { it is LoadState.Failed }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (source in sources.filter { it.isEnabled }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                    when (val state = states[source.id]) {
                        is LoadState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        is LoadState.Loaded -> Text("Ready", style = MaterialTheme.typography.bodySmall)
                        else -> Text("Downloading index…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when (states[source.id]) {
                    is LoadState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    is LoadState.Loaded -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (sources.none { it.isEnabled }) {
            Text("All databases are turned off. Turn one on in Settings → Databases.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Some databases couldn’t be downloaded. Check your internet connection, then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = { scope.launch { database.refreshAll() } }) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Try again")
            }
        }
    }
}
