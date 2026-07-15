package com.example.livetranslate.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.livetranslate.LiveTranslateApp
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.LiveSessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * System overlay showing bilingual live captions.
 *
 * - Rounded corners, configurable size / colors / opacity (Settings)
 * - Lock / unlock via **notification** action (not tap on overlay)
 * - Unlocked → drag to reposition; locked → fixed + touch-through
 */
class SubtitleOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captionJob: Job? = null
    private var settingsJob: Job? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var enView: TextView? = null
    private var zhView: TextView? = null
    private var dividerView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var overlaySettings: UserSettings = UserSettings()

    // Drag state (only when unlocked)
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var dragging = false
    private var touchSlop = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APPLY_LOCK -> {
                applyLockFromState(showToast = intent.getBooleanExtra(EXTRA_SHOW_TOAST, false))
            }
            ACTION_TOGGLE_LOCK -> {
                OverlayLockState.toggle()
                applyLockFromState(showToast = true)
                // Ask recording notification to refresh action label if running
                RecordingService.refreshNotification(this)
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (10 * density).toInt()

        // Both halves: full width, equal vertical weight (50/50 split by divider)
        zhView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 16f
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        enView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        dividerView = View(this).apply {
            setBackgroundColor(Color.argb(100, 255, 255, 255))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Default: translation on top, divider, original below — equal width & height
            addView(zhView, halfRowLp())
            addView(dividerView, dividerLp(density))
            addView(enView, halfRowLp())
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Let panel fill the overlay so weight-based 50/50 works
            isFillViewport = true
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        root = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
                !OverlayLockState.locked

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (OverlayLockState.locked) return false
                return handleDrag(ev)
            }
        }.apply {
            clipToOutline = true
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val dm = resources.displayMetrics
        val widthPx = (overlaySettings.overlayMaxWidthDp * density).toInt()
            .coerceIn(120, dm.widthPixels)
        val heightPx = (overlaySettings.overlayMaxHeightDp * density).toInt()
            .coerceIn(60, dm.heightPixels)
        val defaultX = ((dm.widthPixels - widthPx) / 2).coerceAtLeast(0)
        val defaultY = (dm.heightPixels * 0.72f).toInt().coerceAtLeast(0)
        val maxX = (dm.widthPixels - widthPx).coerceAtLeast(0)
        val maxY = (dm.heightPixels - heightPx).coerceAtLeast(0)
        val saved = OverlayPositionStore.load(this)
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            baseFlags(locked = OverlayLockState.locked),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore last drag position when available; clamp to current screen/size
            x = (saved?.first ?: defaultX).coerceIn(0, maxX)
            y = (saved?.second ?: defaultY).coerceIn(0, maxY)
        }

        applyChrome(overlaySettings)
        applyLockFromState(showToast = false)

        try {
            windowManager?.addView(root, layoutParams)
        } catch (_: Exception) {
            stopSelf()
            return
        }

        val app = application as LiveTranslateApp
        val controller = app.container.sessionController
        val settingsRepo = app.container.settingsRepository

        settingsJob = scope.launch {
            settingsRepo.settings.collect { s ->
                overlaySettings = s
                applyChrome(s)
                applyLayoutSize(s)
                applyStackOrder(s)
            }
        }

        captionJob = scope.launch {
            controller.state
                .map { st -> st.toOverlayCaption() }
                .distinctUntilChanged()
                .collect { cap ->
                    enView?.text = cap.en
                    zhView?.text = cap.zh
                }
        }
    }

    private fun baseFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (locked) {
            // Pass touches through to apps underneath when fixed
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun applyLockFromState(showToast: Boolean) {
        val locked = OverlayLockState.locked
        val lp = layoutParams
        val rootView = root
        if (lp != null && rootView != null) {
            lp.flags = baseFlags(locked)
            try {
                windowManager?.updateViewLayout(rootView, lp)
            } catch (_: Exception) {
            }
        }
        applyChrome(overlaySettings)
        if (showToast) {
            val msg = if (locked) "悬浮窗已锁定" else "悬浮窗已解锁，可拖动"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyChrome(s: UserSettings) {
        val density = resources.displayMetrics.density
        val alphaPct = s.overlayAlphaPercent.coerceIn(0, 100)
        val a = (alphaPct * 255 / 100).coerceIn(0, 255)
        val bgRgb = UserSettings.parseColorHex(s.overlayBgColor, Color.BLACK)
        val r = Color.red(bgRgb)
        val g = Color.green(bgRgb)
        val b = Color.blue(bgRgb)
        val locked = OverlayLockState.locked
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_RADIUS_DP * density
            setColor(Color.argb(a, r, g, b))
        }
        val strokeA = (a * 0.45f).toInt().coerceIn(40, 180)
        val strokeColor = if (locked) {
            Color.argb(strokeA, 120, 120, 120)
        } else {
            Color.argb(strokeA.coerceAtLeast(100), 76, 175, 80)
        }
        bg.setStroke((1.5f * density).toInt().coerceAtLeast(1), strokeColor)
        root?.background = bg

        val enColor = UserSettings.parseColorHex(s.overlayEnTextColor, Color.WHITE)
        val zhColor = UserSettings.parseColorHex(s.overlayZhTextColor, Color.parseColor("#FFEB3B"))
        enView?.setTextColor(enColor)
        zhView?.setTextColor(zhColor)
        // Divider: semi-transparent light line over panel bg
        val divA = (a * 0.55f).toInt().coerceIn(50, 160)
        dividerView?.setBackgroundColor(Color.argb(divA, 220, 220, 220))
    }

    /**
     * Reorder panel children: [top] / divider / [bottom]
     * Default top = translation (ZH), bottom = original (EN).
     * Both rows: MATCH_PARENT width + equal weight (horizontal full / vertical 50-50).
     */
    private fun applyStackOrder(s: UserSettings) {
        val p = panel ?: return
        val zh = zhView ?: return
        val en = enView ?: return
        val div = dividerView ?: return
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (10 * density).toInt()

        val top = if (s.overlayTranslationOnTop) zh else en
        val bottom = if (s.overlayTranslationOnTop) en else zh

        // Symmetric padding & same size so halves look equal
        top.setPadding(padH, padV, padH, padV)
        bottom.setPadding(padH, padV, padH, padV)
        top.textSize = 16f
        bottom.textSize = 16f
        top.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        bottom.gravity = Gravity.CENTER_VERTICAL or Gravity.START

        p.removeAllViews()
        p.addView(top, halfRowLp())
        p.addView(div, dividerLp(density))
        p.addView(bottom, halfRowLp())
    }

    /** Full-width row, shares remaining height equally with the other half. */
    private fun halfRowLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

    private fun dividerLp(density: Float): LinearLayout.LayoutParams {
        val divH = (1 * density).toInt().coerceAtLeast(1)
        // Full width — no side margins (avoids looking narrower than text rows)
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            divH
        )
    }

    private fun applyLayoutSize(s: UserSettings) {
        val lp = layoutParams ?: return
        val rootView = root ?: return
        val dm = resources.displayMetrics
        val density = dm.density
        val maxW = (s.overlayMaxWidthDp * density).toInt().coerceIn(120, dm.widthPixels)
        val maxH = (s.overlayMaxHeightDp * density).toInt().coerceIn(60, dm.heightPixels)
        lp.width = maxW
        lp.height = maxH
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - maxW).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - maxH).coerceAtLeast(0))
        try {
            windowManager?.updateViewLayout(rootView, lp)
        } catch (_: Exception) {
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        val lp = layoutParams ?: return false
        val rootView = root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = lp.x
                downParamY = lp.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val dm = resources.displayMetrics
                    val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0)
                    lp.x = (downParamX + dx.toInt()).coerceIn(0, maxX)
                    lp.y = (downParamY + dy.toInt()).coerceIn(0, maxY)
                    try {
                        windowManager?.updateViewLayout(rootView, lp)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging || event.actionMasked == MotionEvent.ACTION_UP) {
                    persistPosition()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun persistPosition() {
        val lp = layoutParams ?: return
        OverlayPositionStore.save(this, lp.x, lp.y)
    }

    override fun onDestroy() {
        // Remember position even if user locked after dragging, or service stops mid-session
        persistPosition()
        captionJob?.cancel()
        settingsJob?.cancel()
        scope.cancel()
        try {
            root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        root = null
        super.onDestroy()
    }

    companion object {
        private const val CORNER_RADIUS_DP = 14f
        const val ACTION_TOGGLE_LOCK = "com.example.livetranslate.action.OVERLAY_TOGGLE_LOCK"
        const val ACTION_APPLY_LOCK = "com.example.livetranslate.action.OVERLAY_APPLY_LOCK"
        private const val EXTRA_SHOW_TOAST = "show_toast"

        fun start(context: Context) {
            context.startService(Intent(context, SubtitleOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SubtitleOverlayService::class.java))
        }

        fun toggleLock(context: Context) {
            val i = Intent(context, SubtitleOverlayService::class.java)
                .setAction(ACTION_TOGGLE_LOCK)
            context.startService(i)
        }

        /**
         * Live-caption style: show streaming partials when present, otherwise the
         * latest completed line — not the full cumulative transcript.
         */
        internal fun LiveSessionUiState.toOverlayCaption(): OverlayCaption {
            val en = when {
                partialEn.isNotBlank() -> partialEn.trim()
                else -> lastLine(cumulativeEn)
            }
            val zh = when {
                partialZh.isNotBlank() -> partialZh.trim()
                else -> lastLine(cumulativeZh)
            }
            return OverlayCaption(
                en = en.ifBlank { "…" }.takeLast(220),
                zh = zh.ifBlank { "…" }.takeLast(220)
            )
        }

        private fun lastLine(block: String): String {
            if (block.isBlank()) return ""
            return block.trimEnd().substringAfterLast('\n').trim()
        }

        internal data class OverlayCaption(val en: String, val zh: String)
    }
}
