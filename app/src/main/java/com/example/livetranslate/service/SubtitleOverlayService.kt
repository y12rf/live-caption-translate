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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * System overlay showing live captions.
 *
 * - Rounded corners, configurable size / colors / opacity / font / text mode (Settings)
 * - Layout: full sentence (centered) or single-line marquee
 * - Clears text after [IDLE_BLANK_MS] with no new caption content
 * - Lock / unlock via **notification** action (not tap on overlay)
 * - Unlocked → drag to reposition; locked → fixed + touch-through
 */
class SubtitleOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captionJob: Job? = null
    private var settingsJob: Job? = null
    private var idleBlankJob: Job? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var enView: CaptionLineView? = null
    private var zhView: CaptionLineView? = null
    private var dividerView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var overlaySettings: UserSettings = UserSettings()
    /** Last applied layout mode; reset scroll controller only when this changes. */
    private var lastLayoutMode: OverlayLayoutMode? = null
    /** Latest session snapshot for layout-switch catch-up (avoid full replay). */
    private var lastSessionState: LiveSessionUiState = LiveSessionUiState()
    private val scrollController = OverlayScrollController(
        onShow = { cap -> showCaption(cap, isUpdate = false) },
        onBacklog = { depth -> applyCatchUpSpeed(depth) },
        onUpdate = { cap -> showCaption(cap, isUpdate = true) }
    )
    /** How many marquee lines still need to finish for the current caption. */
    private var pendingLineFinishes = 0
    /** Last texts applied to en/zh views (for mid-scroll translation fill-in). */
    private var lastShownEn: String = ""
    private var lastShownZh: String = ""
    /**
     * Fingerprint of the caption we last idle-blanked. FullSentence re-emits the same
     * last line on every session tick — without this, blank would immediately re-show.
     */
    private var idleBlankedFingerprint: String? = null
    /** Prevent deep recursion when advancing through empty ScrollLine captions. */
    private var emptyAdvanceDepth = 0

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
            try {
                settingsRepo.settings.collect { s ->
                    val prevLayout = overlaySettings.overlayLayoutModeEnum()
                    overlaySettings = s
                    scrollController.finishBeforeNext = s.overlayMarqueeFinishBeforeNext
                    val newLayout = s.overlayLayoutModeEnum()
                    if (lastLayoutMode != null && prevLayout != newLayout) {
                        onLayoutModeChanged(newLayout)
                    }
                    applyChrome(s)
                    applyLayoutSize(s)
                    applyStackOrder(s)
                    applyTextStyle(s)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "settings collect failed", e)
            }
        }

        captionJob = scope.launch {
            try {
                controller.state.collect { st ->
                    lastSessionState = st
                    if (st.phase == SessionPhase.Idle) {
                        // Always reset controller on Idle (including save-failed with segments).
                        scrollController.reset()
                        pendingLineFinishes = 0
                        lastShownEn = ""
                        lastShownZh = ""
                        idleBlankedFingerprint = null
                        emptyAdvanceDepth = 0
                        cancelIdleBlankTimer()
                        clearOverlayTextOnly()
                    }
                    val layout = overlaySettings.overlayLayoutModeEnum()
                    if (lastLayoutMode != null && lastLayoutMode != layout) {
                        onLayoutModeChanged(layout)
                    } else {
                        lastLayoutMode = layout
                    }
                    when (layout) {
                        OverlayLayoutMode.ScrollLine -> {
                            if (st.phase != SessionPhase.Idle) {
                                // Committed segments only; queue + finish-before-next
                                scrollController.onState(st, overlaySettings)
                            }
                        }
                        OverlayLayoutMode.FullSentence -> {
                            if (st.phase != SessionPhase.Idle) {
                                val cap = st.toOverlayCaption(overlaySettings)
                                showCaptionImmediate(cap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "caption collect failed", e)
            }
        }
    }

    private fun onLayoutModeChanged(newLayout: OverlayLayoutMode) {
        // Mark existing segments seen so we do not re-marquee the whole session.
        scrollController.resetCatchUp(lastSessionState.segments)
        pendingLineFinishes = 0
        lastShownEn = ""
        lastShownZh = ""
        idleBlankedFingerprint = null
        emptyAdvanceDepth = 0
        cancelIdleBlankTimer()
        clearOverlayTextOnly()
        lastLayoutMode = newLayout
        // Show only the latest line immediately (FullSentence or as ScrollLine seed).
        if (lastSessionState.phase != SessionPhase.Idle) {
            val cap = lastSessionState.toOverlayCaption(overlaySettings)
            when (newLayout) {
                OverlayLayoutMode.FullSentence -> showCaptionImmediate(cap)
                OverlayLayoutMode.ScrollLine -> {
                    if (cap.en.isNotBlank() || cap.zh.isNotBlank()) {
                        showCaption(
                            OverlayScrollController.OverlayCaption(cap.en, cap.zh),
                            isUpdate = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Before reparenting caption views (stack/mode change), cancel marquees so
     * finish counters do not stall the ScrollLine queue.
     */
    private fun interruptScrollLineForChromeChange() {
        if (overlaySettings.overlayLayoutModeEnum() != OverlayLayoutMode.ScrollLine) return
        if (pendingLineFinishes <= 0 && lastShownEn.isEmpty() && lastShownZh.isEmpty()) return
        enView?.cancelScroll()
        zhView?.cancelScroll()
        pendingLineFinishes = 0
        scrollController.notifyDisplayCleared()
    }

    private fun showCaption(
        cap: OverlayScrollController.OverlayCaption,
        isUpdate: Boolean
    ) {
        val layout = overlaySettings.overlayLayoutModeEnum()
        val mode = overlaySettings.overlayTextModeEnum()
        val showEn = mode != OverlayTextMode.TranslationOnly && cap.en.isNotBlank()
        val showZh = mode != OverlayTextMode.SourceOnly &&
            !overlaySettings.asrOnlyMode &&
            cap.zh.isNotBlank()
        val newEn = if (showEn) cap.en else ""
        val newZh = if (showZh) cap.zh else ""

        if (!isUpdate) {
            // Start each caption at the user base speed; backlog callback may boost after
            val baseSpeed = overlaySettings.overlayMarqueeSpeed
                .coerceIn(20, CaptionLineView.SETTINGS_MAX_SPEED.toInt())
                .toFloat()
            enView?.speedPxPerSec = baseSpeed
            zhView?.speedPxPerSec = baseSpeed
            // Count lines that will report finish (marquee or dwell)
            pendingLineFinishes = 0
            if (layout == OverlayLayoutMode.ScrollLine) {
                if (showEn) pendingLineFinishes++
                if (showZh) pendingLineFinishes++
                if (pendingLineFinishes == 0) {
                    // Empty caption: advance without deep recursion
                    lastShownEn = ""
                    lastShownZh = ""
                    advanceEmptyScrollLine()
                    return
                }
            }
            emptyAdvanceDepth = 0
            idleBlankedFingerprint = null
            enView?.setCaptionText(newEn)
            zhView?.setCaptionText(newZh)
            lastShownEn = newEn
            lastShownZh = newZh
            if (layout == OverlayLayoutMode.FullSentence) {
                pendingLineFinishes = 0
            }
            onCaptionContentApplied(newEn, newZh)
            return
        }

        // Mid-scroll / post-show update (typically translation just arrived).
        // Do not reset speeds or finish counters for lines already running.
        if (layout == OverlayLayoutMode.ScrollLine &&
            newZh.isNotBlank() &&
            lastShownZh.isBlank()
        ) {
            pendingLineFinishes++
        }
        idleBlankedFingerprint = null
        enView?.setCaptionText(newEn)
        zhView?.setCaptionText(newZh)
        lastShownEn = newEn
        lastShownZh = newZh
        onCaptionContentApplied(newEn, newZh)
    }

    private fun advanceEmptyScrollLine() {
        emptyAdvanceDepth++
        if (emptyAdvanceDepth > 32) {
            emptyAdvanceDepth = 0
            android.util.Log.w(TAG, "empty ScrollLine advance depth exceeded; stop")
            return
        }
        // Post so stacked empty captions do not blow the call stack
        root?.post {
            scrollController.onScrollFinished()
        } ?: scrollController.onScrollFinished()
    }

    /**
     * When the next committed sentence is already queued, speed up the active
     * marquee so the overlay catches up instead of lagging further behind speech.
     */
    private fun applyCatchUpSpeed(queueDepth: Int) {
        if (queueDepth <= 0) return
        if (overlaySettings.overlayLayoutModeEnum() != OverlayLayoutMode.ScrollLine) return
        if (!overlaySettings.overlayMarqueeFinishBeforeNext) return
        val base = overlaySettings.overlayMarqueeSpeed
            .coerceIn(20, CaptionLineView.SETTINGS_MAX_SPEED.toInt())
            .toFloat()
        val boosted = OverlayScrollController.catchUpSpeed(base, queueDepth)
        enView?.applySpeedNow(boosted)
        zhView?.applySpeedNow(boosted)
    }

    private fun showCaptionImmediate(cap: OverlayCaption) {
        val en = cap.en
        val zh = cap.zh
        val fp = captionFingerprint(en, zh)
        // After idle-blank, ignore re-emissions of the same last line until content changes.
        if (idleBlankedFingerprint != null && fp == idleBlankedFingerprint) {
            return
        }
        // Session state emits often (phase/queues); only treat real caption changes as activity.
        val changed = en != lastShownEn || zh != lastShownZh
        enView?.setCaptionText(en)
        zhView?.setCaptionText(zh)
        if (changed) {
            lastShownEn = en
            lastShownZh = zh
            if (en.isNotBlank() || zh.isNotBlank()) {
                idleBlankedFingerprint = null
            }
            onCaptionContentApplied(en, zh)
        }
    }

    private fun captionFingerprint(en: String, zh: String): String = "$en\u0000$zh"

    private fun onLineMarqueeFinished() {
        if (overlaySettings.overlayLayoutModeEnum() != OverlayLayoutMode.ScrollLine) return
        if (pendingLineFinishes <= 0) return
        pendingLineFinishes--
        if (pendingLineFinishes <= 0) {
            scrollController.onScrollFinished()
        }
    }

    /**
     * Restart the idle-blank timer when non-empty caption text is applied.
     * After [IDLE_BLANK_MS] with no further content, clear the floating text.
     */
    private fun onCaptionContentApplied(en: String, zh: String) {
        if (en.isBlank() && zh.isBlank()) {
            cancelIdleBlankTimer()
            return
        }
        scheduleIdleBlank()
    }

    private fun scheduleIdleBlank() {
        idleBlankJob?.cancel()
        idleBlankJob = scope.launch {
            delay(IDLE_BLANK_MS)
            blankOverlayAfterIdle()
        }
    }

    private fun cancelIdleBlankTimer() {
        idleBlankJob?.cancel()
        idleBlankJob = null
    }

    private fun blankOverlayAfterIdle() {
        val fp = captionFingerprint(lastShownEn, lastShownZh)
        if (fp != "\u0000") {
            idleBlankedFingerprint = fp
        }
        clearOverlayTextOnly()
        pendingLineFinishes = 0
        lastShownEn = ""
        lastShownZh = ""
        // Allow next queued ScrollLine caption (if any) after we force-clear mid-scroll.
        if (overlaySettings.overlayLayoutModeEnum() == OverlayLayoutMode.ScrollLine) {
            scrollController.notifyDisplayCleared()
        }
    }

    /** Clear caption text without tearing down the panel chrome. */
    private fun clearOverlayTextOnly() {
        enView?.cancelScroll()
        zhView?.cancelScroll()
        enView?.setCaptionText("")
        zhView?.setCaptionText("")
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
        // 0 = fully transparent background; 100 = solid. Must allow true zero.
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
        if (s.overlayShowBorder && a > 0) {
            // Scale stroke with background opacity; never force a min when alpha is 0.
            val strokeA = (a * 0.45f).toInt().coerceIn(1, 180)
            val strokeColor = if (locked) {
                Color.argb(strokeA, 120, 120, 120)
            } else {
                Color.argb(strokeA.coerceAtLeast(minOf(100, a)), 76, 175, 80)
            }
            bg.setStroke((1.5f * density).toInt().coerceAtLeast(1), strokeColor)
        } else {
            // Opacity 0 or border off → no stroke (true transparent panel)
            bg.setStroke(0, Color.TRANSPARENT)
        }
        root?.background = bg

        val enColor = UserSettings.parseColorHex(s.overlayEnTextColor, Color.WHITE)
        val zhColor = UserSettings.parseColorHex(s.overlayZhTextColor, Color.parseColor("#FFEB3B"))
        enView?.setTextColorInt(enColor)
        zhView?.setTextColorInt(zhColor)
        // Divider: scales with background opacity; fully gone at 0
        if (s.overlayShowDivider && a > 0) {
            val divA = (a * 0.55f).toInt().coerceIn(1, 160)
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

        // removeAllViews detaches children and cancels marquee without finish callbacks.
        interruptScrollLineForChromeChange()
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
        cancelIdleBlankTimer()
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
        private const val TAG = "SubtitleOverlay"
        private const val CORNER_RADIUS_DP = 14f
        /** Clear floating caption text after this long without new content. */
        const val IDLE_BLANK_MS = 3_000L
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
            // Empty content stays blank — no "…" placeholder.
            val en = when (mode) {
                OverlayTextMode.TranslationOnly -> ""
                else -> enRaw.takeLast(220)
            }
            val zh = when (mode) {
                OverlayTextMode.SourceOnly -> ""
                else -> zhRaw.takeLast(220)
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
