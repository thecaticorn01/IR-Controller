package com.thecaticorn01.ircontroller

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thecaticorn01.ircontroller.system.KeyDispatch
import com.thecaticorn01.ircontroller.system.ShortcutPublisher
import com.thecaticorn01.ircontroller.ui.App
import com.thecaticorn01.ircontroller.ui.theme.IRTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Something another app or the launcher asked us to do. */
sealed interface IncomingAction {
    data class OpenRemote(val remoteId: String) : IncomingAction
    data class Import(val uris: List<Uri>) : IncomingAction
}

class MainActivity : ComponentActivity() {
    private val incoming = Channel<IncomingAction>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            IRTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                App(incoming = incoming.receiveAsFlow())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // USB access may have been granted or the dongle replugged while we were away.
        container.blaster.scan()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> container.blaster.scan()
            ShortcutPublisher.ACTION_OPEN_REMOTE -> intent.getStringExtra(ShortcutPublisher.EXTRA_REMOTE_ID)?.let {
                incoming.trySend(IncomingAction.OpenRemote(it))
            }
            Intent.ACTION_VIEW -> intent.data?.let { incoming.trySend(IncomingAction.Import(listOf(it))) }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                incoming.trySend(IncomingAction.Import(listOf(it)))
            }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }
                ?.let { incoming.trySend(IncomingAction.Import(it)) }
        }
    }

    // Overriding is supported; the restriction only guards androidx-internal callers.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyDispatch.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
