package com.thecaticorn01.ircontroller.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote

/**
 * What a remote button can do. Provided once near the root so buttons deep in
 * the tree (keypads, previews, widgets in lists) don't need callbacks threaded through.
 */
interface RemoteActions {
    /** Sends a button with the blaster; failures are reported by the app. */
    fun send(button: IRButton, extraRepeats: Int = 0)
    fun copyPronto(button: IRButton)
    fun editButton(remoteId: String, buttonId: String)
    fun deleteButton(remoteId: String, buttonId: String)
    fun pinButton(remote: IRRemote, button: IRButton)
    val hapticsEnabled: Boolean
}

val LocalRemoteActions = staticCompositionLocalOf<RemoteActions> {
    error("RemoteActions not provided")
}
