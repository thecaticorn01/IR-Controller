package com.thecaticorn01.ircontroller.system

import android.view.KeyEvent

/**
 * Routes hardware key presses (keyboard arrows, digits, the phone's volume keys)
 * to the remote currently on screen. The activity forwards every key event here
 * before normal handling, so it works regardless of which view has focus.
 */
object KeyDispatch {
    private val handlers = ArrayDeque<(KeyEvent) -> Boolean>()

    fun push(handler: (KeyEvent) -> Boolean) {
        handlers.addLast(handler)
    }

    fun remove(handler: (KeyEvent) -> Boolean) {
        handlers.remove(handler)
    }

    /** Returns true if the topmost handler consumed the event. */
    fun dispatch(event: KeyEvent): Boolean = handlers.lastOrNull()?.invoke(event) ?: false
}
