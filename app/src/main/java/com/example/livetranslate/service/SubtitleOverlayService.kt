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
import android.widget.Toast
import com.example.livetranslate.LiveTranslateApp
import com.example.livetranslate.R
import com.example.livetranslate.data.settings.OverlayLayoutMode
import com.example.livetranslate.data.settings.OverlayTextMode
import com.example.livetranslate.data.settings.UserSettings
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.model.SessionPhase
import com.example.livetranslate.ui.caption.CaptionLineView
import com.example.livetranslate.ui.caption.OverlayScrollController
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
 * System overlay showing live captions.
 *
 * - Rounded corners, configurable size / colors / opacity / font / text mode (Settings)
 * - Layout: full sentence (centered) or single-line marquee
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
    private var enView: CaptionLineView? = null
    private var zhView: CaptionLineView? = null
    private var dividerView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var overlaySettings: UserSettings = UserSettings()
    private val scrollController = OverlayScrollController { cap -> showCaption(cap) }
    /** How many marquee lines still need to finish for the current caption. */
    private var pendingLineFinishes = 0

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
        val padH = (UserSettings.DEFAULT_OVERLAY_PAD_H_DP * density).toInt()
        val padV = (UserSettings.DEFAULT_OVERLAY_PAD_V_DP * density).toInt()

        // Both halves: full width, equal vertical weight (50/50 split by divider)
        zhView = CaptionLineView(this).apply {
            setTextColorInt(Color.parseColor("#FFEB3B"))
            setTextSizeSp(16f)
            setPadding(padH, padV, padH, padV)
            onScrollFinished = { onLineMarqueeFinished() }
        }
        enView = CaptionLineView(this).apply {
            setTextColorInt(Color.WHITE)
            setTextSizeSp(16f)
            setPadding(padH, padV, padH, padV)
            onScrollFinished = { onLineMarqueeFinished() }
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
        // Per-resolution slot only; unknown resolution → default placement
        val saved = OverlayPositionStore.load(this, dm)
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            baseFlags(locked = OverlayLockState.locked),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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
                scrollController.finishBeforeNext = s.overlayMarqueeFinishBeforeNext
                applyChrome(s)
                applyLayoutSize(s)
                applyStackOrder(s)
                applyTextStyle(s)
            }
        }

        captionJob = scope.launch {
            controller.state.collect { st ->
                if (st.phase == SessionPhase.Idle && st.segments.isEmpty()) {
                    scrollController.reset()
                    pendingLineFinishes = 0
                }
                when (overlaySettings.overlayLayoutModeEnum()) {
                    OverlayLayoutMode.ScrollLine -> {
                        // Committed segments only; queue + finish-before-next
                        scrollController.onState(st, overlaySettings)
                    }
                    OverlayLayoutMode.FullSentence -> {
                        scrollController.reset()
                        val cap = st.toOverlayCaption(overlaySettings)
                        showCaptionImmediate(cap)
                    }
                }
            }
        }
    }

    private fun showCaption(cap: OverlayScrollController.OverlayCaption) {
        val layout = overlaySettings.overlayLayoutModeEnum()
        val mode = overlaySettings.overlayTextModeEnum()
        val showEn = mode != OverlayTextMode.TranslationOnly && cap.en.isNotBlank()
        val showZh = mode != OverlayTextMode.SourceOnly &&
            !overlaySettings.asrOnlyMode &&
            cap.zh.isNotBlank()
        // Count lines that will report finish (marquee or dwell)
        pendingLineFinishes = 0
        if (layout == OverlayLayoutMode.ScrollLine) {
            if (showEn) pendingLineFinishes++
            if (showZh) pendingLineFinishes++
            if (pendingLineFinishes == 0) {
                // Empty caption: advance immediately
                scrollController.onScrollFinished()
                return
            }
        }
        enView?.setCaptionText(if (showEn) cap.en else "")
        zhView?.setCaptionText(if (showZh) cap.zh else "")
        if (layout == OverlayLayoutMode.FullSentence) {
            pendingLineFinishes = 0
        }
    }

    private fun showCaptionImmediate(cap: OverlayCaption) {
        enView?.setCaptionText(cap.en)
        zhView?.setCaptionText(cap.zh)
    }

    private fun onLineMarqueeFinished() {
        if (overlaySettings.overlayLayoutModeEnum() != OverlayLayoutMode.ScrollLine) return
        if (pendingLineFinishes <= 0) return
        pendingLineFinishes--
        if (pendingLineFinishes <= 0) {
            scrollController.onScrollFinished()
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
            val msg = getString(
                if (locked) R.string.overlay_locked else R.string.overlay_unlocked
            )
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
        if (s.overlayShowBorder) {
            val strokeA = (a * 0.45f).toInt().coerceIn(40, 180)
            val strokeColor = if (locked) {
                Color.argb(strokeA, 120, 120, 120)
            } else {
                Color.argb(strokeA.coerceAtLeast(100), 76, 175, 80)
            }
            bg.setStroke((1.5f * density).toInt().coerceAtLeast(1), strokeColor)
        } else {
            bg.setStroke(0, Color.TRANSPARENT)
        }
        root?.background = bg

        val enColor = UserSettings.parseColorHex(s.overlayEnTextColor, Color.WHITE)
        val zhColor = UserSettings.parseColorHex(s.overlayZhTextColor, Color.parseColor("#FFEB3B"))
        enView?.setTextColorInt(enColor)
        zhView?.setTextColorInt(zhColor)
        // Divider: semi-transparent light line (or fully hidden via settings)
        if (s.overlayShowDivider) {
            val divA = (a * 0.55f).toInt().coerceIn(50, 160)
            dividerView?.setBackgroundColor(Color.argb(divA, 220, 220, 220))
        } else {
            dividerView?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /**
     * Reorder panel children based on text mode + stack order.
     * Default top = translation (ZH), bottom = original (EN).
     */
    private fun applyStackOrder(s: UserSettings) {
        val p = panel ?: return
        val zh = zhView ?: return
        val en = enView ?: return
        val div = dividerView ?: return
        val density = resources.displayMetrics.density
        val mode = s.overlayTextModeEnum()

        p.removeAllViews()
        when (mode) {
            OverlayTextMode.SourceOnly -> {
                en.visibility = View.VISIBLE
                zh.visibility = View.GONE
                div.visibility = View.GONE
                p.addView(en, halfRowLp())
            }
            OverlayTextMode.TranslationOnly -> {
                zh.visibility = View.VISIBLE
                en.visibility = View.GONE
                div.visibility = View.GONE
                p.addView(zh, halfRowLp())
            }
            OverlayTextMode.Both -> {
                en.visibility = View.VISIBLE
                zh.visibility = View.VISIBLE
                val top = if (s.overlayTranslationOnTop) zh else en
                val bottom = if (s.overlayTranslationOnTop) en else zh
                p.addView(top, halfRowLp())
                if (s.overlayShowDivider) {
                    div.visibility = View.VISIBLE
                    p.addView(div, dividerLp(density))
                } else {
                    div.visibility = View.GONE
                }
                p.addView(bottom, halfRowLp())
            }
        }
        applyTextStyle(s)
    }

    /** Font size, padding, full-sentence vs marquee speed. */
    private fun applyTextStyle(s: UserSettings) {
        val density = resources.displayMetrics.density
        val padH = (s.overlayPadHDp.coerceIn(0, 32) * density).toInt()
        val padV = (s.overlayPadVDp.coerceIn(0, 24) * density).toInt()
        val fontSp = s.overlayFontSizeSp.coerceIn(10, 48).toFloat()
        val layout = s.overlayLayoutModeEnum()
        val speed = s.overlayMarqueeSpeed.coerceIn(20, 160).toFloat()
        listOfNotNull(enView, zhView).forEach { tv ->
            tv.setPadding(padH, padV, padH, padV)
            tv.setTextSizeSp(fontSp)
            tv.speedPxPerSec = speed
            when (layout) {
                OverlayLayoutMode.FullSentence ->
                    tv.setMode(CaptionLineView.Mode.FullSentence)
                OverlayLayoutMode.ScrollLine ->
                    tv.setMode(CaptionLineView.Mode.Marquee)
            }
        }
        // Full sentence: enable vertical scroll; marquee: single line rows
        scroll?.isFillViewport = true
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
        val dm = resources.displayMetrics
        OverlayPositionStore.save(this, lp.x, lp.y, dm)
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
         * Honors [UserSettings.overlayTextMode] for which lines are filled.
         */
        internal fun LiveSessionUiState.toOverlayCaption(
            settings: UserSettings = UserSettings()
        ): OverlayCaption {
            val enRaw = when {
                partialEn.isNotBlank() -> partialEn.trim()
                else -> lastLine(cumulativeEn)
            }
            val zhRaw = when {
                partialZh.isNotBlank() -> partialZh.trim()
                else -> lastLine(cumulativeZh)
            }
            val mode = settings.overlayTextModeEnum()
            val en = when (mode) {
                OverlayTextMode.TranslationOnly -> ""
                else -> enRaw.ifBlank { "…" }.takeLast(220)
            }
            val zh = when (mode) {
                OverlayTextMode.SourceOnly -> ""
                else -> zhRaw.ifBlank {
                    if (settings.asrOnlyMode) "" else "…"
                }.takeLast(220)
            }
            return OverlayCaption(en = en, zh = zh)
        }

        private fun lastLine(block: String): String {
            if (block.isBlank()) return ""
            return block.trimEnd().substringAfterLast('\n').trim()
        }

        internal data class OverlayCaption(val en: String, val zh: String)
    }
}
