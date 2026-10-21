package com.thecaticorn01.ircontroller.widget

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.thecaticorn01.ircontroller.MainActivity
import com.thecaticorn01.ircontroller.container
import com.thecaticorn01.ircontroller.system.PressResult
import com.thecaticorn01.ircontroller.system.ShortcutPublisher
import com.thecaticorn01.ircontroller.system.pressButton
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind
import com.thecaticorn01.ircontroller.core.model.RemoteLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val remoteIdKey = ActionParameters.Key<String>("remoteId")
private val buttonIdKey = ActionParameters.Key<String>("buttonId")

/** Home-screen remote with the most useful buttons of a favourite remote. */
class QuickRemoteWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        container.library.awaitLoaded()
        val settings = container.settings.current()
        val remotes = container.library.remotes.value
        val remote = selectedRemote(remotes, settings.widgetRemoteId)
        val canCycle = candidates(remotes).size > 1
        val openIntent = remote?.let { ShortcutPublisher.openRemoteIntent(context, it.id) }
            ?: Intent(context, MainActivity::class.java)
        provideContent {
            GlanceTheme {
                WidgetContent(remote, canCycle, openIntent)
            }
        }
    }

    companion object {
        fun candidates(remotes: List<IRRemote>) = remotes.filter { it.isFavorite }.ifEmpty { remotes }

        fun selectedRemote(remotes: List<IRRemote>, preferredId: String?): IRRemote? =
            remotes.firstOrNull { it.id == preferredId } ?: candidates(remotes).firstOrNull()

        /** The buttons worth a place on a small widget, in keypad order. */
        fun keyButtons(remote: IRRemote): List<Pair<String, IRButton>> {
            val layout = RemoteLayout(remote.buttons)
            val picks = listOfNotNull(
                layout.power?.let { "Power" to it },
                layout.input?.let { "Input" to it },
                layout.mute?.let { "Mute" to it },
                layout.volumeUp?.let { "Vol +" to it },
                layout.up?.let { "▲" to it },
                layout.channelUp?.let { "CH +" to it },
                layout.left?.let { "◀" to it },
                layout.ok?.let { "OK" to it },
                layout.right?.let { "▶" to it },
                layout.volumeDown?.let { "Vol −" to it },
                layout.down?.let { "▼" to it },
                layout.channelDown?.let { "CH −" to it },
            )
            val used = picks.map { it.second.id }.toSet()
            val extra = remote.buttons.filter { it.id !in used && it.role.kind != Kind.DIGIT }.map { it.displayName to it }
            return picks + extra
        }

        suspend fun updateAll(context: Context) {
            runCatching { QuickRemoteWidget().updateAll(context) }
        }
    }
}

@Composable
private fun WidgetContent(remote: IRRemote?, canCycle: Boolean, openIntent: Intent) {
    val size = LocalSize.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = remote?.name ?: "IR Controller",
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openIntent)),
            )
            if (canCycle) {
                Text(
                    text = "⇄",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionRunCallback<CycleRemoteAction>()),
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (remote == null || remote.buttons.isEmpty()) {
            Text(
                text = if (remote == null) "Add a remote in the app to control it from here." else "This remote has no buttons yet.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity(openIntent)),
            )
            return@Column
        }
        val columns = (size.width.value / 64f).toInt().coerceIn(2, 5)
        val rows = ((size.height.value - 44f) / 46f).toInt().coerceIn(1, 6)
        val buttons = QuickRemoteWidget.keyButtons(remote).take(columns * rows)
        for (row in buttons.chunked(columns)) {
            Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                row.forEachIndexed { index, (label, button) ->
                    if (index > 0) Spacer(GlanceModifier.width(6.dp))
                    WidgetButton(label, remote.id, button, GlanceModifier.defaultWeight())
                }
                repeat(columns - row.size) {
                    Spacer(GlanceModifier.width(6.dp))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun WidgetButton(label: String, remoteId: String, button: IRButton, modifier: GlanceModifier) {
    val isPower = button.role.kind == Kind.POWER
    Box(
        modifier = modifier
            .height(40.dp)
            .background(if (isPower) GlanceTheme.colors.errorContainer else GlanceTheme.colors.secondaryContainer)
            .cornerRadius(20.dp)
            .clickable(actionRunCallback<PressButtonAction>(actionParametersOf(remoteIdKey to remoteId, buttonIdKey to button.id))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (isPower) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onSecondaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

class PressButtonAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val result = context.container.pressButton(remoteId = parameters[remoteIdKey], buttonId = parameters[buttonIdKey])
        if (result is PressResult.Failed) {
            withContext(Dispatchers.Main) { Toast.makeText(context, result.message, Toast.LENGTH_LONG).show() }
        }
    }
}

class CycleRemoteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.container
        container.library.awaitLoaded()
        val remotes = container.library.remotes.value
        val candidates = QuickRemoteWidget.candidates(remotes)
        if (candidates.isEmpty()) return
        val current = QuickRemoteWidget.selectedRemote(remotes, container.settings.current().widgetRemoteId)
        val index = candidates.indexOfFirst { it.id == current?.id }
        container.settings.setWidgetRemoteId(candidates[(index + 1) % candidates.size].id)
    }
}

class QuickRemoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickRemoteWidget()
}
