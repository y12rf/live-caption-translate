package com.example.livetranslate.service

import android.content.Intent

/**
 * Holds MediaProjection consent on the main process only.
 * Avoids re-parceling the capture Intent through Service start extras
 * (fragile on some OEM devices and can crash after grant).
 */
object ProjectionTokenStore {
    @Volatile
    var resultCode: Int = 0
        private set

    @Volatile
    var data: Intent? = null
        private set

    fun put(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        // Copy so the original Activity result Intent is not mutated/recycled
        this.data = Intent(data)
    }

    fun clear() {
        resultCode = 0
        data = null
    }

    fun take(): Pair<Int, Intent>? {
        val code = resultCode
        val intent = data
        clear()
        if (code == 0 || intent == null) return null
        return code to intent
    }
}
