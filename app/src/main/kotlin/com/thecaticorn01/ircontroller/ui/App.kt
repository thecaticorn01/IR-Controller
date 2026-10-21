package com.thecaticorn01.ircontroller.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.thecaticorn01.ircontroller.AppContainer
import com.thecaticorn01.ircontroller.IncomingAction
import com.thecaticorn01.ircontroller.UiMessage
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.data.LibraryRepository
import com.thecaticorn01.ircontroller.system.ShortcutPublisher
import com.thecaticorn01.ircontroller.ui.browse.BrowseScreen
import com.thecaticorn01.ircontroller.ui.browse.PowerScanScreen
import com.thecaticorn01.ircontroller.ui.browse.PreviewScreen
import com.thecaticorn01.ircontroller.ui.components.LocalRemoteActions
import com.thecaticorn01.ircontroller.ui.components.RemoteActions
import com.thecaticorn01.ircontroller.ui.learn.LearnScreen
import com.thecaticorn01.ircontroller.ui.library.AddRemoteSheet
import com.thecaticorn01.ircontroller.ui.library.ButtonEditorScreen
import com.thecaticorn01.ircontroller.ui.library.LibraryScreen
import com.thecaticorn01.ircontroller.ui.library.RemoteDetailScreen
import com.thecaticorn01.ircontroller.ui.library.RemoteInfoDialog
import com.thecaticorn01.ircontroller.ui.settings.DatabaseSettingsScreen
import com.thecaticorn01.ircontroller.ui.settings.SettingsScreen
import com.thecaticorn01.ircontroller.core.formats.IRCodeImporter
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

// Routes

@Serializable data object RemotesGraph
@Serializable data object BrowseGraph
@Serializable data object LearnGraph
@Serializable data object SettingsGraph

@Serializable data object LibraryRoute
@Serializable data class RemoteRoute(val id: String)
@Serializable data class ButtonEditorRoute(val remoteId: String, val buttonId: String? = null, val learn: Boolean = false)
@Serializable data object BrowseRoute
@Serializable data class PreviewRoute(val entryId: String)
@Serializable data object PowerScanRoute
@Serializable data object LearnRoute
@Serializable data object SettingsRoute
@Serializable data object DatabaseSettingsRoute

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val graph: Any) {
    REMOTES("Remotes", Icons.Outlined.SettingsRemote, Icons.Rounded.SettingsRemote, RemotesGraph),
    BROWSE("Browse", Icons.Outlined.Explore, Icons.Rounded.Explore, BrowseGraph),
    LEARN("Learn", Icons.Outlined.WifiTethering, Icons.Rounded.WifiTethering, LearnGraph),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings, SettingsGraph),
}

/** Which remote the new/edit details dialog is for. */
sealed interface RemoteInfoTarget {
    data object New : RemoteInfoTarget
    data class Edit(val remoteId: String) : RemoteInfoTarget
}

/** Navigation and app-wide UI actions shared by every screen. */
@Stable
class AppController(
    val navController: NavHostController,
    val container: AppContainer,
    private val context: Context,
    private val scope: CoroutineScope,
    private val clipboard: Clipboard,
) {
    var isExpanded by mutableStateOf(false)
    var remoteInfo by mutableStateOf<RemoteInfoTarget?>(null)
    var showAddRemote by mutableStateOf(false)
    /** Remote shown beside the list on large screens. */
    var selectedRemoteId by mutableStateOf<String?>(null)

    internal var launchImport: () -> Unit = {}
    internal var launchExport: (fileName: String, content: String) -> Unit = { _, _ -> }

    fun openTab(tab: Tab) {
        navController.navigate(tab.graph) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openRemote(id: String) {
        selectedRemoteId = id
        // Lets the launcher rank the remote's shortcut by how often it's used.
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, "remote-$id") }
        if (isExpanded) {
            if (!navController.popBackStack<LibraryRoute>(inclusive = false)) navController.navigate(LibraryRoute)
        } else {
            navController.navigate(RemoteRoute(id)) {
                popUpTo<LibraryRoute> { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    fun openPreview(entryId: String) {
        container.database.selectedEntryId.value = entryId
        if (!isExpanded) navController.navigate(PreviewRoute(entryId))
    }

    fun openLearn() = openTab(Tab.LEARN)
    fun openBrowse() = openTab(Tab.BROWSE)

    fun addButton(remoteId: String, learn: Boolean) = navController.navigate(ButtonEditorRoute(remoteId, learn = learn))
    fun editButton(remoteId: String, buttonId: String) = navController.navigate(ButtonEditorRoute(remoteId, buttonId))

    fun importFiles() = launchImport()

    fun import(uris: List<android.net.Uri>) {
        scope.launch {
            val summary = container.library.importUris(uris)
            summary.imported.firstOrNull()?.let { openRemote(it.id) }
            if (summary.messages.isNotEmpty()) {
                container.show(
                    UiMessage(
                        if (summary.imported.isEmpty()) "Import failed" else "Imported with warnings",
                        summary.messages.joinToString("\n"),
                        isAlert = true,
                    ),
                )
            } else if (summary.imported.size > 1) {
                container.show(UiMessage("Imported ${summary.imported.size} remotes"))
            }
        }
    }

    /** Saves a remote as a Flipper Zero `.ir` file with the system file picker. */
    fun exportRemote(remote: IRRemote) =
        launchExport(LibraryRepository.fileName(remote, ".ir"), container.library.exportFlipper(remote))

    /** Saves the whole library as a JSON backup. */
    fun exportLibrary() = launchExport("IR_Controller_Library.json", container.library.exportArchive())

    /** Shares a remote's `.ir` file through the Android share sheet. */
    fun shareRemote(remote: IRRemote) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, LibraryRepository.fileName(remote, ".ir"))
        file.writeText(container.library.exportFlipper(remote))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, remote.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${remote.name}"))
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun pinRemote(remote: IRRemote) {
        if (!ShortcutPublisher.pinRemote(context, remote)) {
            container.show(UiMessage("Your launcher doesn’t support pinned shortcuts"))
        }
    }

    fun copyText(label: String, text: String, confirmation: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
            container.show(UiMessage(confirmation))
        }
    }
}

val LocalAppController = staticCompositionLocalOf<AppController> { error("AppController not provided") }

@Composable
fun App(incoming: Flow<IncomingAction>) {
    val context = LocalContext.current
    val container = context.container
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val controller = remember { AppController(navController, container, context, scope, clipboard) }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var alert by remember { mutableStateOf<UiMessage?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.import(uris)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            scope.launch {
                runCatching { container.library.writeDocument(uri, content) }
                    .onSuccess { container.show(UiMessage("Exported")) }
                    .onFailure { container.showError(it, "Export failed") }
            }
        }
    }
    controller.launchImport = { importLauncher.launch(arrayOf("*/*")) }
    controller.launchExport = { name, content ->
        pendingExport = content
        exportLauncher.launch(name)
    }

    val actions = remember(settings.haptics) {
        object : RemoteActions {
            override fun send(button: IRButton, extraRepeats: Int) = container.send(button, extraRepeats)
            override fun copyPronto(button: IRButton) {
                val raw = runCatching { button.signal.rendered(toggle = false) }.getOrNull() ?: return
                controller.copyText("Pronto hex", IRCodeImporter.prontoHex(raw), "Copied Pronto hex for “${button.displayName}”")
            }
            override fun editButton(remoteId: String, buttonId: String) = controller.editButton(remoteId, buttonId)
            override fun deleteButton(remoteId: String, buttonId: String) {
                val remote = container.library.remote(remoteId) ?: return
                val button = remote.buttons.firstOrNull { it.id == buttonId } ?: return
                container.library.deleteButton(buttonId, remoteId)
                scope.launch {
                    val result = snackbarHostState.showSnackbar("Deleted “${button.displayName}”", "Undo", duration = SnackbarDuration.Short)
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        val index = remote.buttons.indexOfFirst { it.id == buttonId }
                        container.library.modify(remoteId) { current ->
                            val list = current.buttons.toMutableList()
                            list.add(index.coerceIn(0, list.size), button)
                            current.copy(buttons = list)
                        }
                    }
                }
            }
            override fun pinButton(remote: IRRemote, button: IRButton) {
                if (!ShortcutPublisher.pinButton(context, remote, button)) {
                    container.show(UiMessage("Your launcher doesn’t support pinned shortcuts"))
                }
            }
            override val hapticsEnabled: Boolean = settings.haptics
        }
    }

    LaunchedEffect(Unit) {
        container.messages.collect { message ->
            if (message.isAlert) {
                alert = message
            } else {
                val text = if (message.message.isEmpty()) message.title else "${message.title}. ${message.message}"
                // Don't queue a pile of identical errors when several buttons are tapped.
                if (snackbarHostState.currentSnackbarData?.visuals?.message == text) return@collect
                snackbarHostState.currentSnackbarData?.dismiss()
                scope.launch { snackbarHostState.showSnackbar(text, withDismissAction = message.message.isNotEmpty()) }
            }
        }
    }
    LaunchedEffect(Unit) {
        incoming.collect { action ->
            when (action) {
                is IncomingAction.OpenRemote -> {
                    container.library.awaitLoaded()
                    if (container.library.remote(action.remoteId) != null) controller.openRemote(action.remoteId)
                }
                is IncomingAction.Import -> controller.import(action.uris)
            }
        }
    }
    val loadError by container.library.loadError.collectAsStateWithLifecycle()
    LaunchedEffect(loadError) {
        loadError?.let { container.show(UiMessage("Your remotes couldn’t be loaded", it, isAlert = true)) }
    }

    CompositionLocalProvider(LocalAppController provides controller, LocalRemoteActions provides actions) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            SideEffect { controller.isExpanded = expanded }
            val backStackEntry by navController.currentBackStackEntryAsState()
            val destination = backStackEntry?.destination
            val selectedTab = Tab.entries.firstOrNull { tab ->
                destination?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true
            } ?: Tab.REMOTES
            val isTopLevel = destination?.hierarchy?.any {
                it.hasRoute(LibraryRoute::class) || it.hasRoute(BrowseRoute::class) || it.hasRoute(LearnRoute::class) || it.hasRoute(SettingsRoute::class)
            } ?: true
            val adaptiveInfo = currentWindowAdaptiveInfoV2()
            val defaultType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
            // Detail screens get the whole phone screen; rails stay visible on larger screens.
            val layoutType = if (!isTopLevel && defaultType == NavigationSuiteType.NavigationBar) NavigationSuiteType.None else defaultType

            NavigationSuiteScaffold(
                layoutType = layoutType,
                navigationSuiteItems = {
                    for (tab in Tab.entries) {
                        item(
                            selected = tab == selectedTab,
                            onClick = {
                                if (tab == selectedTab) {
                                    // Re-selecting a tab returns to its first screen.
                                    navController.popBackStack(tab.graph, inclusive = false)
                                    val start = when (tab) {
                                        Tab.REMOTES -> LibraryRoute
                                        Tab.BROWSE -> BrowseRoute
                                        Tab.LEARN -> LearnRoute
                                        Tab.SETTINGS -> SettingsRoute
                                    }
                                    navController.popBackStack(start, inclusive = false)
                                } else {
                                    controller.openTab(tab)
                                }
                            },
                            icon = { Icon(if (tab == selectedTab) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(navController)
                    SnackbarHost(snackbarHostState, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            }
        }

        controller.remoteInfo?.let { target ->
            RemoteInfoDialog(target = target, onDismiss = { controller.remoteInfo = null })
        }
        if (controller.showAddRemote) {
            AddRemoteSheet(onDismiss = { controller.showAddRemote = false })
        }
        alert?.let { message ->
            AlertDialog(
                onDismissRequest = { alert = null },
                confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } },
                title = { Text(message.title) },
                text = { Text(message.message) },
            )
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = RemotesGraph,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        navigation<RemotesGraph>(startDestination = LibraryRoute) {
            composable<LibraryRoute> { LibraryScreen() }
            composable<RemoteRoute> { entry ->
                RemoteDetailScreen(remoteId = entry.toRoute<RemoteRoute>().id, onBack = { navController.navigateUp() })
            }
            composable<ButtonEditorRoute> { entry ->
                val route = entry.toRoute<ButtonEditorRoute>()
                ButtonEditorScreen(
                    remoteId = route.remoteId,
                    buttonId = route.buttonId,
                    startInLearnMode = route.learn,
                    onDone = { navController.navigateUp() },
                )
            }
        }
        navigation<BrowseGraph>(startDestination = BrowseRoute) {
            composable<BrowseRoute> { BrowseScreen() }
            composable<PreviewRoute> { entry ->
                PreviewScreen(entryId = entry.toRoute<PreviewRoute>().entryId, onBack = { navController.navigateUp() })
            }
            composable<PowerScanRoute> { PowerScanScreen(onDone = { navController.navigateUp() }) }
        }
        navigation<LearnGraph>(startDestination = LearnRoute) {
            composable<LearnRoute> { LearnScreen() }
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> { SettingsScreen(onOpenDatabases = { navController.navigate(DatabaseSettingsRoute) }) }
            composable<DatabaseSettingsRoute> { DatabaseSettingsScreen(onBack = { navController.navigateUp() }) }
        }
    }
}
