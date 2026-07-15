package com.example.livetranslate.service

/**
 * In-process lock state for the subtitle overlay.
 * Locked (default): fixed, touch-through. Unlocked: draggable.
 */
object OverlayLockState {
    @Volatile
    var locked: Boolean = true

    fun toggle(): Boolean {
        locked = !locked
        return locked
    }
}
