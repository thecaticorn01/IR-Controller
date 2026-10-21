package com.thecaticorn01.ircontroller

import android.app.Application
import android.content.Context
import com.thecaticorn01.ircontroller.blaster.BlasterManager
import com.thecaticorn01.ircontroller.data.DatabaseRepository
import com.thecaticorn01.ircontroller.data.LibraryRepository
import com.thecaticorn01.ircontroller.data.SecretStore
import com.thecaticorn01.ircontroller.data.SettingsRepository
import com.thecaticorn01.ircontroller.system.ShortcutPublisher
import com.thecaticorn01.ircontroller.widget.QuickRemoteWidget
import com.thecaticorn01.ircontroller.core.model.IRButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

open class IRApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Whether to download stale database indexes at launch (screenshot tests run offline). */
    protected open val refreshDatabasesOnLaunch: Boolean = true

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start(refreshDatabases = refreshDatabasesOnLaunch)
    }
}

val Context.container: AppContainer get() = (applicationContext as IRApplication).container

/** A message for the user: shown as a snackbar, or as a dialog when [isAlert] is set. */
data class UiMessage(val title: String, val message: String = "", val isAlert: Boolean = false)

/** App-wide singletons. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(context, scope)
    val secrets = SecretStore(context)
    val library = LibraryRepository(context, scope)
    val database = DatabaseRepository(context, scope, secrets)
    val blaster = BlasterManager(context, scope)

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun show(message: UiMessage) {
        _messages.tryEmit(message)
    }

    fun showError(error: Throwable, title: String = "Something went wrong") =
        show(UiMessage(title, error.message ?: error.toString()))

    /** Sends a button with the user's extra-repeat setting, reporting failures in the UI. */
    fun send(button: IRButton, extraRepeats: Int = 0) {
        scope.launch {
            try {
                blaster.send(button.signal, button.name, extraRepeats + settings.settings.value.extraRepeats)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e, "Couldn’t send “${button.displayName}”")
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun start(refreshDatabases: Boolean = true) {
        blaster.start()
        if (refreshDatabases) scope.launch { database.refreshIfNeeded() }
        // Keep launcher shortcuts and the home-screen widget in step with the library.
        scope.launch {
            combine(library.remotes, settings.settings.map { it.widgetRemoteId }) { remotes, widgetId -> remotes to widgetId }
                .drop(1)
                .debounce(500)
                .distinctUntilChanged()
                .collect { (remotes, _) ->
                    ShortcutPublisher.publishFavorites(context, remotes)
                    QuickRemoteWidget.updateAll(context)
                }
        }
    }
}
