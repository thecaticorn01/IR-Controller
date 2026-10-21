package com.thecaticorn01.ircontroller.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.RemoteInfoTarget
import com.thecaticorn01.ircontroller.ui.components.BlasterStatusButton
import com.thecaticorn01.ircontroller.ui.components.CategoryBadge
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.core.model.IRRemote
import java.text.NumberFormat

@Composable
fun LibraryScreen() {
    val app = LocalAppController.current
    val remotes by app.container.library.remotes.collectAsStateWithLifecycle()
    if (app.isExpanded && remotes.isNotEmpty()) {
        val selected = remotes.firstOrNull { it.id == app.selectedRemoteId } ?: remotes.first()
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                RemoteList(remotes, selectedId = selected.id, onOpen = { app.selectedRemoteId = it.id })
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                RemoteDetailScreen(remoteId = selected.id, onBack = null)
            }
        }
    } else {
        RemoteList(remotes, selectedId = null, onOpen = { app.openRemote(it.id) })
    }
}

@Composable
private fun RemoteList(remotes: List<IRRemote>, selectedId: String?, onOpen: (IRRemote) -> Unit) {
    val app = LocalAppController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("My Remotes") },
                subtitle = {
                    if (remotes.isNotEmpty()) {
                        val favorites = remotes.count { it.isFavorite }
                        Text("${remotes.size} remote${if (remotes.size == 1) "" else "s"}" + if (favorites > 0) " · $favorites favourite${if (favorites == 1) "" else "s"}" else "")
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    BlasterStatusButton()
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("New empty remote") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, null) },
                                onClick = { menuOpen = false; app.remoteInfo = RemoteInfoTarget.New },
                            )
                            DropdownMenuItem(
                                text = { Text("Import files") },
                                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                                onClick = { menuOpen = false; app.importFiles() },
                            )
                            DropdownMenuItem(
                                text = { Text("Back up library") },
                                leadingIcon = { Icon(Icons.Rounded.Backup, null) },
                                enabled = remotes.isNotEmpty(),
                                onClick = { menuOpen = false; app.exportLibrary() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (remotes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { app.showAddRemote = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add remote") },
                )
            }
        },
    ) { padding ->
        if (remotes.isEmpty()) {
            Welcome(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(remotes, key = { _, remote -> remote.id }) { index, remote ->
                    RemoteRow(
                        remote = remote,
                        selected = remote.id == selectedId,
                        index = index,
                        count = remotes.size,
                        onOpen = { onOpen(remote) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(
    remote: IRRemote,
    selected: Boolean,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppController.current
    val library = app.container.library
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ListItem(
            selected = selected,
            onClick = onOpen,
            modifier = modifier.fillMaxWidth(),
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            leadingContent = { CategoryBadge(remote.category, size = 44.dp) },
            supportingContent = {
                val details = listOf(remote.brandAndModel, "${remote.buttons.size} buttons").filter { it.isNotBlank() }
                Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { library.toggleFavorite(remote.id) }) {
                        Icon(
                            if (remote.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (remote.isFavorite) "Remove from favourites" else "Add to favourites",
                            tint = if (remote.isFavorite) androidx.compose.ui.graphics.Color(0xFFF5B400) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options for ${remote.name}") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            RemoteMenuItems(
                                remote = remote,
                                canMoveUp = index > 0,
                                canMoveDown = index < count - 1,
                                onDismiss = { menuOpen = false },
                                onDelete = { confirmDelete = true },
                            )
                        }
                    }
                }
            },
        ) { Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) }
    if (confirmDelete) {
        DeleteRemoteDialog(remote, onDismiss = { confirmDelete = false })
    }
}

/** Menu entries shared by the remote list and the remote screen. */
@Composable
fun RemoteMenuItems(
    remote: IRRemote,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    showFavorite: Boolean = false,
) {
    val app = LocalAppController.current
    val library = app.container.library
    val context = LocalContext.current
    if (showFavorite) {
        DropdownMenuItem(
            text = { Text(if (remote.isFavorite) "Remove from favourites" else "Add to favourites") },
            leadingIcon = { Icon(if (remote.isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star, null) },
            onClick = { onDismiss(); library.toggleFavorite(remote.id) },
        )
    }
    DropdownMenuItem(
        text = { Text("Edit details") },
        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
        onClick = { onDismiss(); app.remoteInfo = RemoteInfoTarget.Edit(remote.id) },
    )
    DropdownMenuItem(
        text = { Text("Duplicate") },
        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
        onClick = { onDismiss(); library.duplicate(remote.id)?.let { app.openRemote(it.id) } },
    )
    DropdownMenuItem(
        text = { Text("Export as Flipper .ir") },
        leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
        onClick = { onDismiss(); app.exportRemote(remote) },
    )
    DropdownMenuItem(
        text = { Text("Share") },
        leadingIcon = { Icon(Icons.Rounded.Share, null) },
        onClick = { onDismiss(); app.shareRemote(remote) },
    )
    if (com.thecaticorn01.ircontroller.system.ShortcutPublisher.canPin(context)) {
        DropdownMenuItem(
            text = { Text("Add to home screen") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, null) },
            onClick = { onDismiss(); app.pinRemote(remote) },
        )
    }
    if (canMoveUp || canMoveDown) HorizontalDivider()
    if (canMoveUp) {
        DropdownMenuItem(
            text = { Text("Move up") },
            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
            onClick = { onDismiss(); library.move(remote.id, -1) },
        )
    }
    if (canMoveDown) {
        DropdownMenuItem(
            text = { Text("Move down") },
            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
            onClick = { onDismiss(); library.move(remote.id, 1) },
        )
    }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Delete") },
        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
        onClick = { onDismiss(); onDelete() },
    )
}

@Composable
fun DeleteRemoteDialog(remote: IRRemote, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val app = LocalAppController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Delete “${remote.name}”?") },
        text = { Text("This removes the remote and all of its buttons from your library.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (app.selectedRemoteId == remote.id) app.selectedRemoteId = null
                app.container.library.delete(remote.id)
                onDeleted()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown when the library is empty, and on first launch. */
@Composable
private fun Welcome(modifier: Modifier = Modifier) {
    val app = LocalAppController.current
    val entries by app.container.database.allEntries.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(Icons.Rounded.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Text(
                "Your phone is now a remote",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Control TVs, projectors, air conditioners and more with your ELK Smart USB-C IR blaster.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val count = if (entries.isNotEmpty()) NumberFormat.getIntegerInstance().format(entries.size) else "thousands of"
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3,
            ) {
                WelcomeCard(
                    "Find your remote",
                    "Search $count remotes from Flipper-IRDB and IRDB, try them, and add them in one tap.",
                    Icons.Rounded.Explore,
                    Modifier.weight(1f).widthIn(min = 200.dp),
                ) { app.openBrowse() }
                WelcomeCard(
                    "Learn a remote",
                    "Point your existing remote at the blaster and teach the app each button.",
                    Icons.Rounded.WifiTethering,
                    Modifier.weight(1f).widthIn(min = 200.dp),
                ) { app.openLearn() }
                WelcomeCard(
                    "Import a file",
                    "Open Flipper Zero .ir files, IRDB .csv files or a library backup.",
                    Icons.Rounded.FileOpen,
                    Modifier.weight(1f).widthIn(min = 200.dp),
                ) { app.importFiles() }
            }
            TextButton(onClick = { app.remoteInfo = RemoteInfoTarget.New }) {
                Icon(Icons.AutoMirrored.Rounded.NoteAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Create an empty remote")
            }
        }
    }
}

@Composable
private fun WelcomeCard(title: String, detail: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(28.dp), modifier = modifier) {
        Column(Modifier.padding(20.dp).height(150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bottom sheet with every way of adding a remote. */
@Composable
fun AddRemoteSheet(onDismiss: () -> Unit) {
    val app = LocalAppController.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Add a remote", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetOption(Icons.Rounded.Explore, "Find in online databases", "Flipper-IRDB, IRDB and your own sources") {
                onDismiss(); app.openBrowse()
            }
            SheetOption(Icons.Rounded.WifiTethering, "Learn a new remote", "Capture buttons from a physical remote") {
                onDismiss(); app.openLearn()
            }
            SheetOption(Icons.AutoMirrored.Rounded.NoteAdd, "Create an empty remote", "Add buttons later by learning or entering codes") {
                onDismiss(); app.remoteInfo = RemoteInfoTarget.New
            }
            SheetOption(Icons.Rounded.FileOpen, "Import files", "Flipper .ir, IRDB .csv or a library backup (.json)") {
                onDismiss(); app.importFiles()
            }
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        supportingContent = { Text(detail) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    ) { Text(title) }
}
