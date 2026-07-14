package com.example.livetranslate.service

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Holds MediaProjection consent on the main process only.
 * Do not put the capture Intent into Service start extras — keep it here.
 *
 * Note: [Activity.RESULT_OK] is **-1**, not a positive code.
 */
object ProjectionTokenStore {
    private const val TAG = "ProjectionTokenStore"

    @Volatile
    private var resultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    private var data: Intent? = null

    @Synchronized
    fun put(resultCode: Int, data: Intent) {
        // Keep the original Intent reference (Binder extras must stay valid)
        this.resultCode = resultCode
        this.data = data
        Log.i(TAG, "put resultCode=$resultCode dataExtras=${data.extras?.keySet()}")
    }

    @Synchronized
    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        data = null
    }

    @Synchronized
    fun hasToken(): Boolean =
        resultCode == Activity.RESULT_OK && data != null

    /**
     * Peek without clearing (so a failed start can retry once in the same grant).
     */
    @Synchronized
    fun peek(): Pair<Int, Intent>? {
        val code = resultCode
        val intent = data
        if (code != Activity.RESULT_OK || intent == null) {
            Log.w(TAG, "peek empty: resultCode=$code dataNull=${intent == null}")
            return null
        }
        return code to intent
    }

    @Synchronized
    fun take(): Pair<Int, Intent>? {
        val pair = peek() ?: return null
        // Clear only after successful handoff to getMediaProjection by caller
        // Caller should call [clear] after MediaProjection is created.
        return pair
    }

    @Synchronized
    fun consume() {
        clear()
    }
}
