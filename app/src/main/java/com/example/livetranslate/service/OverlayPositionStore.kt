package com.example.livetranslate.service

import android.content.Context
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists floating-subtitle drag positions **per screen resolution**.
 *
 * - Several (width×height → x,y) slots can coexist.
 * - [save] overwrites the slot for the current resolution only.
 * - [load] returns the slot that matches current resolution, or null
 *   (caller uses default placement for unseen resolutions).
 *
 * Coordinates are WindowManager raw x/y (TOP|START gravity), in pixels.
 */
object OverlayPositionStore {
    private const val PREFS = "overlay_position"
    private const val KEY_SLOTS = "slots_v2"
    /** Legacy single-position keys (migrated once if present). */
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_HAS = "has"
    private const val MAX_SLOTS = 12

    data class ScreenKey(val widthPx: Int, val heightPx: Int) {
        fun label(): String = "${widthPx}x${heightPx}"
    }

    fun screenKey(dm: DisplayMetrics): ScreenKey =
        ScreenKey(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))

    fun save(context: Context, x: Int, y: Int, dm: DisplayMetrics) {
        save(context, x, y, screenKey(dm))
    }

    fun save(context: Context, x: Int, y: Int, key: ScreenKey) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val slots = loadAllSlots(prefs).toMutableMap()
        slots[key] = x to y
        // Cap growth: drop oldest-unrelated by keeping most recently written last
        val ordered = slots.entries.toList()
        val trimmed = if (ordered.size > MAX_SLOTS) {
            ordered.takeLast(MAX_SLOTS).associate { it.key to it.value }
        } else {
            slots
        }
        prefs.edit()
            .putString(KEY_SLOTS, encodeSlots(trimmed))
            // Clear legacy single-slot keys after multi-slot is authoritative
            .remove(KEY_HAS)
            .remove(KEY_X)
            .remove(KEY_Y)
            .apply()
    }

    /**
     * Position for the given screen size, or null if this resolution was never saved.
     */
    fun load(context: Context, dm: DisplayMetrics): Pair<Int, Int>? =
        load(context, screenKey(dm))

    fun load(context: Context, key: ScreenKey): Pair<Int, Int>? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val slots = loadAllSlots(prefs)
        return slots[key]
    }

    private fun loadAllSlots(
        prefs: android.content.SharedPreferences
    ): Map<ScreenKey, Pair<Int, Int>> {
        val raw = prefs.getString(KEY_SLOTS, null)
        if (!raw.isNullOrBlank()) {
            return decodeSlots(raw)
        }
        // One-shot migrate legacy single position → cannot know old resolution;
        // leave empty so new default applies until user drags once on this screen.
        if (prefs.getBoolean(KEY_HAS, false)) {
            // Drop legacy; next drag will create a proper per-resolution slot.
            prefs.edit().remove(KEY_HAS).remove(KEY_X).remove(KEY_Y).apply()
        }
        return emptyMap()
    }

    private fun encodeSlots(slots: Map<ScreenKey, Pair<Int, Int>>): String {
        val arr = JSONArray()
        for ((k, pos) in slots) {
            arr.put(
                JSONObject()
                    .put("w", k.widthPx)
                    .put("h", k.heightPx)
                    .put("x", pos.first)
                    .put("y", pos.second)
            )
        }
        return arr.toString()
    }

    private fun decodeSlots(raw: String): Map<ScreenKey, Pair<Int, Int>> {
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<ScreenKey, Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optInt("w", 0)
                val h = o.optInt("h", 0)
                if (w <= 0 || h <= 0) continue
                out[ScreenKey(w, h)] = o.optInt("x", 0) to o.optInt("y", 0)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
