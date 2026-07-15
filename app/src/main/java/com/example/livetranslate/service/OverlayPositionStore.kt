package com.example.livetranslate.service

import android.content.Context

/**
 * Persists the last drag position of the subtitle overlay across process restarts.
 * Coordinates are WindowManager raw x/y (TOP|START gravity), in pixels.
 */
object OverlayPositionStore {
    private const val PREFS = "overlay_position"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_HAS = "has"

    fun save(context: Context, x: Int, y: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS, true)
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    /** @return last (x, y) or null if never saved */
    fun load(context: Context): Pair<Int, Int>? {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_HAS, false)) return null
        return p.getInt(KEY_X, 0) to p.getInt(KEY_Y, 0)
    }
}
