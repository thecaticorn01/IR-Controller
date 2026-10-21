package com.thecaticorn01.ircontroller.system

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.thecaticorn01.ircontroller.MainActivity
import com.thecaticorn01.ircontroller.R
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote

/**
 * Launcher shortcuts: long-pressing the app icon opens a favourite remote, and
 * any button can be pinned to the home screen to press it without opening the app.
 */
object ShortcutPublisher {
    const val ACTION_OPEN_REMOTE = "com.thecaticorn01.ircontroller.action.OPEN_REMOTE"
    const val ACTION_PRESS_BUTTON = "com.thecaticorn01.ircontroller.action.PRESS_BUTTON"
    const val EXTRA_REMOTE_ID = "remoteId"
    const val EXTRA_BUTTON_ID = "buttonId"

    fun openRemoteIntent(context: Context, remoteId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_REMOTE)
            .putExtra(EXTRA_REMOTE_ID, remoteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pressButtonIntent(context: Context, remoteId: String, buttonId: String): Intent =
        Intent(context, PressButtonActivity::class.java)
            .setAction(ACTION_PRESS_BUTTON)
            .putExtra(EXTRA_REMOTE_ID, remoteId)
            .putExtra(EXTRA_BUTTON_ID, buttonId)

    /** Publishes up to four favourite remotes (or the first remotes) as dynamic shortcuts. */
    fun publishFavorites(context: Context, remotes: List<IRRemote>) {
        val candidates = remotes.filter { it.isFavorite }.ifEmpty { remotes }
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, 4)
        val shortcuts = candidates.take(max).mapIndexed { index, remote ->
            ShortcutInfoCompat.Builder(context, "remote-${remote.id}")
                .setShortLabel(remote.name.take(25))
                .setLongLabel(remote.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(openRemoteIntent(context, remote.id))
                .setRank(index)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        // Keep pinned shortcuts for deleted remotes from doing anything surprising.
        val existingIds = remotes.map { "remote-${it.id}" }.toSet()
        val pinned = runCatching {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        }.getOrDefault(emptyList())
        val stale = pinned.map { it.id }.filter { it.startsWith("remote-") && it !in existingIds }
        if (stale.isNotEmpty()) runCatching { ShortcutManagerCompat.disableShortcuts(context, stale, "This remote was deleted.") }
    }

    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut that presses [button] on [remote]. */
    fun pinButton(context: Context, remote: IRRemote, button: IRButton): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val info = ShortcutInfoCompat.Builder(context, "button-${remote.id}-${button.id}")
            .setShortLabel(button.displayName.take(25))
            .setLongLabel("${button.displayName} · ${remote.name}")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(pressButtonIntent(context, remote.id, button.id))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Asks the launcher to pin a shortcut that opens [remote]. */
    fun pinRemote(context: Context, remote: IRRemote): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val info = ShortcutInfoCompat.Builder(context, "remote-${remote.id}")
            .setShortLabel(remote.name.take(25))
            .setLongLabel(remote.name)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(openRemoteIntent(context, remote.id))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}
