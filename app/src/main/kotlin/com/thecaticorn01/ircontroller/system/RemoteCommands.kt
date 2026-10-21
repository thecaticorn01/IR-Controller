package com.thecaticorn01.ircontroller.system

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.thecaticorn01.ircontroller.AppContainer
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import kotlinx.coroutines.launch

/** Outcome of pressing a button from outside the app's UI. */
sealed interface PressResult {
    data class Sent(val remote: IRRemote, val button: IRButton) : PressResult
    data class Failed(val message: String) : PressResult
}

/**
 * Presses a button on a saved remote without showing the app: used by pinned
 * shortcuts, the home-screen widget and automation broadcasts.
 */
suspend fun AppContainer.pressButton(
    remoteId: String? = null,
    buttonId: String? = null,
    remoteName: String? = null,
    buttonName: String? = null,
    repeats: Int = 0,
): PressResult {
    library.awaitLoaded()
    val remote = remoteId?.let { library.remote(it) } ?: remoteName?.let { library.remoteNamed(it) }
        ?: return PressResult.Failed(
            if (remoteName != null) "There is no remote named “$remoteName”." else "That remote no longer exists.",
        )
    val button = buttonId?.let { id -> remote.buttons.firstOrNull { it.id == id } } ?: buttonName?.let { remote.button(it) }
        ?: return PressResult.Failed(
            if (buttonName != null) "“${remote.name}” has no button named “$buttonName”." else "That button no longer exists.",
        )
    if (!blaster.awaitReady()) {
        return PressResult.Failed("No IR blaster is connected. Plug in your ELK Smart USB-C IR blaster.")
    }
    return try {
        blaster.send(button.signal, button.name, repeats.coerceIn(0, 20) + settings.current().extraRepeats)
        PressResult.Sent(remote, button)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        PressResult.Failed(e.message ?: e.toString())
    }
}

/** Invisible activity started by pinned "press button" shortcuts. */
class PressButtonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val remoteId = intent.getStringExtra(ShortcutPublisher.EXTRA_REMOTE_ID)
        val buttonId = intent.getStringExtra(ShortcutPublisher.EXTRA_BUTTON_ID)
        val app = applicationContext
        val container = app.container
        container.scope.launch {
            val message = when (val result = container.pressButton(remoteId = remoteId, buttonId = buttonId)) {
                is PressResult.Sent -> null
                is PressResult.Failed -> result.message
            }
            if (message != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

/**
 * Lets automation apps (Tasker, Home Assistant…) press a button.
 * Disabled until the user allows it in Settings.
 *
 * ```
 * am broadcast -a com.thecaticorn01.ircontroller.action.PRESS_BUTTON \
 *   -n com.thecaticorn01.ircontroller/.system.AutomationReceiver \
 *   --es remote "Living Room TV" --es button "Power" --ei repeat 0
 * ```
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShortcutPublisher.ACTION_PRESS_BUTTON) return
        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                if (!container.settings.current().allowAutomation) {
                    container.blaster.record("Ignored an automation request: automation is turned off in Settings", isError = true)
                    pending.resultCode = RESULT_DISABLED
                    return@launch
                }
                val result = container.pressButton(
                    remoteId = intent.getStringExtra(EXTRA_REMOTE_ID),
                    buttonId = intent.getStringExtra(EXTRA_BUTTON_ID),
                    remoteName = intent.getStringExtra(EXTRA_REMOTE),
                    buttonName = intent.getStringExtra(EXTRA_BUTTON),
                    repeats = intent.getIntExtra(EXTRA_REPEAT, 0),
                )
                when (result) {
                    is PressResult.Sent -> {
                        pending.resultCode = RESULT_SENT
                        pending.resultData = "Sent ${result.button.displayName} to ${result.remote.name}."
                    }
                    is PressResult.Failed -> {
                        pending.resultCode = RESULT_FAILED
                        pending.resultData = result.message
                        container.blaster.record("Automation: ${result.message}", isError = true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_BUTTON = "button"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_REMOTE_ID = ShortcutPublisher.EXTRA_REMOTE_ID
        const val EXTRA_BUTTON_ID = ShortcutPublisher.EXTRA_BUTTON_ID
        const val RESULT_SENT = 1
        const val RESULT_FAILED = 2
        const val RESULT_DISABLED = 3
    }
}
