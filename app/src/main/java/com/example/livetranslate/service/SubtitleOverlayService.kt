package com.example.livetranslate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.livetranslate.LiveTranslateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * System overlay showing bilingual subtitles (EN + ZH).
 * Requires SYSTEM_ALERT_WINDOW / overlay permission.
 */
class SubtitleOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var enView: TextView? = null
    private var zhView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        enView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad / 2)
            maxLines = 4
        }
        zhView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 15f
            setPadding(pad, pad / 2, pad, pad)
            maxLines = 4
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            addView(enView)
            addView(zhView)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (48 * density).toInt()
        }
        try {
            windowManager?.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
            return
        }

        val controller = (application as LiveTranslateApp).container.sessionController
        job = scope.launch {
            controller.state.collectLatest { st ->
                val en = buildDisplay(st.cumulativeEn, st.partialEn)
                val zh = buildDisplay(st.cumulativeZh, st.partialZh)
                enView?.text = en.takeLast(280)
                zhView?.text = zh.takeLast(280)
            }
        }
    }

    private fun buildDisplay(cumulative: String, partial: String): String {
        return when {
            cumulative.isEmpty() && partial.isEmpty() -> "…"
            cumulative.isEmpty() -> partial
            partial.isEmpty() -> cumulative
            else -> cumulative + "\n" + partial
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        try {
            root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        root = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, SubtitleOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SubtitleOverlayService::class.java))
        }
    }
}
