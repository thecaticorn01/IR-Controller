package com.thecaticorn01.ircontroller.ui.library

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.data.RemoteDisplayMode
import com.thecaticorn01.ircontroller.system.KeyDispatch
import com.thecaticorn01.ircontroller.ui.LocalAppController
import com.thecaticorn01.ircontroller.ui.components.BlasterStatusButton
import com.thecaticorn01.ircontroller.ui.components.ButtonGrid
import com.thecaticorn01.ircontroller.ui.components.CategoryBadge
import com.thecaticorn01.ircontroller.ui.components.EmptyState
import com.thecaticorn01.ircontroller.ui.components.LocalRemoteActions
import com.thecaticorn01.ircontroller.ui.components.RemoteKeypad
import com.thecaticorn01.ircontroller.ui.theme.icon
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind
import com.thecaticorn01.ircontroller.core.model.RemoteLayout
import com.thecaticorn01.ircontroller.core.model.searchKey

/** Shows one remote as a physical-style keypad, or as an editable list of buttons. */
@Composable
fun RemoteDetailScreen(remoteId: String, onBack: (() -> Unit)?) {
    val app = LocalAppController.current
    val container = app.container
    val remotes by container.library.remotes.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val remote = remotes.firstOrNull { it.id == remoteId }
    if (remote == null) {
        EmptyState(Icons.Rounded.SettingsRemote, "Remote not found", "It may have been deleted.") {
            if (onBack != null) TextButton(onClick = onBack) { Text("Go back") }
        }
        return
    }

    var filter by rememberSaveable { mutableStateOf("") }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var filterFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val displayMode = settings.remoteDisplayMode
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    RemoteKeyHandler(remote, enabled = !filterFocused)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    Text(
                        listOf(remote.brandAndModel, "${remote.buttons.size} buttons").filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { container.library.toggleFavorite(remote.id) }) {
                        Icon(
                            if (remote.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (remote.isFavorite) "Remove from favourites" else "Add to favourites",
                            tint = if (remote.isFavorite) androidx.compose.ui.graphics.Color(0xFFF5B400) else androidx.compose.material3.LocalContentColor.current,
                        )
                    }
                    BlasterStatusButton()
                    Box {
                        IconButton(onClick = { addMenuOpen = true }) { Icon(Icons.Rounded.Add, "Add button") }
                        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Learn from remote") },
                                leadingIcon = { Icon(Icons.Rounded.WifiTethering, null) },
                                onClick = { addMenuOpen = false; app.addButton(remote.id, learn = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Enter a code") },
                                leadingIcon = { Icon(Icons.Rounded.Numbers, null) },
                                onClick = { addMenuOpen = false; app.addButton(remote.id, learn = false) },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            RemoteMenuItems(
                                remote = remote,
                                canMoveUp = false,
                                canMoveDown = false,
                                onDismiss = { menuOpen = false },
                                onDelete = { confirmDelete = true },
                            )
                            remote.origin?.url?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("View source on GitHub") },
                                    leadingIcon = { Icon(Icons.Rounded.Language, null) },
                                    onClick = { menuOpen = false; app.openUrl(url) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            if (remote.buttons.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f).widthIn(max = 360.dp)) {
                        SegmentedButton(
                            selected = displayMode == RemoteDisplayMode.KEYPAD,
                            onClick = { container.settings.setRemoteDisplayMode(RemoteDisplayMode.KEYPAD) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = { Icon(Icons.Rounded.Dialpad, null, Modifier.size(18.dp)) },
                        ) { Text("Remote") }
                        SegmentedButton(
                            selected = displayMode == RemoteDisplayMode.LIST,
                            onClick = { container.settings.setRemoteDisplayMode(RemoteDisplayMode.LIST) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = { Icon(Icons.AutoMirrored.Rounded.ViewList, null, Modifier.size(18.dp)) },
                        ) { Text("Buttons") }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = {
                        showFilter = !showFilter
                        if (!showFilter) filter = ""
                    }) {
                        Icon(if (showFilter) Icons.Rounded.Close else Icons.Rounded.Search, if (showFilter) "Hide filter" else "Filter buttons")
                    }
                }
                if (showFilter) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter buttons") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .onFocusChanged { filterFocused = it.isFocused },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    remote.buttons.isEmpty() -> EmptyState(
                        Icons.Rounded.SettingsRemote,
                        "No buttons yet",
                        "Teach this remote its buttons with your IR blaster, or enter codes manually.",
                    ) {
                        Button(onClick = { app.addButton(remote.id, learn = true) }) {
                            Icon(Icons.Rounded.WifiTethering, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Learn a button")
                        }
                        FilledTonalButton(onClick = { app.addButton(remote.id, learn = false) }) {
                            Icon(Icons.Rounded.Numbers, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enter a code")
                        }
                    }
                    displayMode == RemoteDisplayMode.KEYPAD -> KeypadView(remote, filter, padding.calculateBottomPadding())
                    else -> ButtonList(remote, filter, padding.calculateBottomPadding())
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteRemoteDialog(remote, onDismiss = { confirmDelete = false }, onDeleted = { onBack?.invoke() })
    }
}

private fun filtered(buttons: List<IRButton>, filter: String): List<IRButton> {
    val key = filter.searchKey
    return if (key.isEmpty()) buttons else buttons.filter { it.name.searchKey.contains(key) }
}

@Composable
private fun KeypadView(remote: IRRemote, filter: String, bottomPadding: androidx.compose.ui.unit.Dp) {
    val visible = filtered(remote.buttons, filter)
    val layout = remember(visible) { RemoteLayout(visible) }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            RemoteHeader(remote)
            if (filter.isEmpty() && layout.hasKeypad) {
                RemoteKeypad(layout, remote, Modifier.fillMaxWidth())
            }
            val extra = if (filter.isEmpty()) layout.others else visible
            if (extra.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (filter.isEmpty() && extra.size != visible.size) {
                        Text("More buttons", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ButtonGrid(extra, remote)
                }
            } else if (filter.isNotEmpty()) {
                Text("No buttons match “$filter”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Title block above the keypad. */
@Composable
private fun RemoteHeader(remote: IRRemote) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CategoryBadge(remote.category, size = 56.dp)
        Text(
            listOf(remote.brand, remote.model, remote.category.displayName).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        remote.origin?.let {
            Text(
                "Imported from ${it.sourceName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (remote.notes.isNotBlank()) {
            Text(remote.notes, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/** Reorderable, renameable list of a remote's buttons. */
@Composable
private fun ButtonList(remote: IRRemote, filter: String, bottomPadding: androidx.compose.ui.unit.Dp) {
    val app = LocalAppController.current
    val actions = LocalRemoteActions.current
    val visible = filtered(remote.buttons, filter)
    var renaming by remember { mutableStateOf<IRButton?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(visible, key = { _, button -> button.id }) { index, button ->
            var menuOpen by remember { mutableStateOf(false) }
                ListItem(
                    onClick = { actions.send(button) },
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = "More options",
                    modifier = Modifier.animateItem(),
                    shapes = ListItemDefaults.segmentedShapes(index, visible.size),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = {
                        Icon(button.role.icon ?: Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = {
                        Text(button.signal.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options for ${button.displayName}") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                                    onClick = { menuOpen = false; renaming = button },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit code") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menuOpen = false; app.editButton(remote.id, button.id) },
                                )
                                if (filter.isEmpty()) {
                                    if (index > 0) {
                                        DropdownMenuItem(
                                            text = { Text("Move up") },
                                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                                            onClick = { menuOpen = false; app.container.library.moveButton(button.id, -1, remote.id) },
                                        )
                                    }
                                    if (index < visible.lastIndex) {
                                        DropdownMenuItem(
                                            text = { Text("Move down") },
                                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                            onClick = { menuOpen = false; app.container.library.moveButton(button.id, 1, remote.id) },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text("Copy Pronto hex") },
                                    leadingIcon = { Icon(Icons.Rounded.Numbers, null) },
                                    onClick = { menuOpen = false; actions.copyPronto(button) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; actions.deleteButton(remote.id, button.id) },
                                )
                            }
                        }
                    },
                ) { Text(button.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    renaming?.let { button ->
        var name by remember(button.id) { mutableStateOf(button.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename button") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        app.container.library.updateButton(button.copy(name = name.trim()), remote.id)
                        renaming = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Hardware keys press remote buttons while this remote is on screen: arrows move,
 * Enter selects, Space plays/pauses, +/− change volume, M mutes, P toggles power,
 * [ and ] change channel and digits enter numbers. The phone's volume keys can
 * control the remote's volume too (Settings).
 */
@Composable
private fun RemoteKeyHandler(remote: IRRemote, enabled: Boolean) {
    val app = LocalAppController.current
    val settings by app.container.settings.settings.collectAsStateWithLifecycle()
    val actions = LocalRemoteActions.current
    val layout = remember(remote.buttons) { RemoteLayout(remote.buttons) }
    val current by rememberUpdatedState(Triple(layout, settings, enabled))
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val (l, s, active) = current
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            val button = when {
                isVolumeKey && s.volumeKeys -> when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> l.volumeUp
                    KeyEvent.KEYCODE_VOLUME_DOWN -> l.volumeDown
                    else -> l.mute
                }
                isVolumeKey -> null
                !active || !s.keyboardShortcuts -> null
                event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> null
                else -> buttonForKey(event, l)
            } ?: return@handler false
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || isVolumeKey)) actions.send(button)
            true
        }
        KeyDispatch.push(handler)
        onDispose { KeyDispatch.remove(handler) }
    }
}

private fun buttonForKey(event: KeyEvent, layout: RemoteLayout): IRButton? = when (event.keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> layout.up ?: layout.channelUp
    KeyEvent.KEYCODE_DPAD_DOWN -> layout.down ?: layout.channelDown
    KeyEvent.KEYCODE_DPAD_LEFT -> layout.left
    KeyEvent.KEYCODE_DPAD_RIGHT -> layout.right
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> layout.ok
    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ESCAPE -> layout.navigation.firstOrNull { it.role.kind == Kind.BACK }
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
        layout.transport.firstOrNull { it.role.kind == Kind.PLAY_PAUSE } ?: layout.transport.firstOrNull { it.role.kind == Kind.PLAY }
    KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> layout.volumeUp
    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> layout.volumeDown
    KeyEvent.KEYCODE_M -> layout.mute
    KeyEvent.KEYCODE_P -> layout.power
    KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_PAGE_UP -> layout.channelUp
    KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_PAGE_DOWN -> layout.channelDown
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> layout.digits[event.keyCode - KeyEvent.KEYCODE_0]
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> layout.digits[event.keyCode - KeyEvent.KEYCODE_NUMPAD_0]
    else -> null
}
