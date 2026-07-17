package com.example.livetranslate.ui.caption

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.max

/**
 * Overlay caption line: full multi-line static text, or single-line horizontal marquee
 * with **configurable speed** (px/s). Fires [onScrollFinished] after one full pass
 * (or a short dwell when text fits without scrolling).
 *
 * Marquee travel ends when the **last glyph is fully visible at the right edge**
 * of the content area (right-aligned end), not after the text has scrolled off
 * the left edge.
 *
 * Supports mid-scroll catch-up via [applySpeedNow] / [setCatchUpDepth]: when the
 * next sentence is already queued, raise speed and shorten dwell so the line
 * finishes sooner.
 */
class CaptionLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { FullSentence, Marquee }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(16f)
    }
    private var mode: Mode = Mode.FullSentence
    private var text: String = ""
    private var staticLayout: StaticLayout? = null
    private var textWidthPx: Float = 0f
    private var scrollOffset: Float = 0f
    private var animator: ValueAnimator? = null
    /** Full travel distance of the active marquee (0 when dwelling / idle). */
    private var marqueeDistance: Float = 0f
    /** True while a no-scroll dwell timer is scheduled. */
    private var dwelling: Boolean = false
    /**
     * Pending queue depth behind the active caption (0 = no catch-up).
     * Drives initial speed boost, shorter dwell, and mid-scroll apply.
     */
    private var catchUpDepth: Int = 0

    /**
     * Active marquee speed in px/s. Settings use 20–160; catch-up may go higher
     * (up to [CATCH_UP_MAX_SPEED]).
     */
    var speedPxPerSec: Float = DEFAULT_SPEED_PX_S
        set(value) {
            field = value.coerceIn(MIN_SPEED, CATCH_UP_MAX_SPEED)
        }

    var onScrollFinished: (() -> Unit)? = null

    private var finishPosted = false
    private val dwellRunnable = Runnable {
        dwelling = false
        if (!finishPosted) {
            finishPosted = true
            onScrollFinished?.invoke()
        }
    }
    private val deferredSpeedRunnable = Runnable {
        if (!finishPosted && mode == Mode.Marquee && text.isNotEmpty()) {
            applySpeedNow(speedPxPerSec)
        }
    }

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        stopAnim()
        rebuildLayout()
        invalidate()
    }

    fun setTextColorInt(color: Int) {
        textPaint.color = color
        invalidate()
    }

    fun setTextSizeSp(sp: Float) {
        textPaint.textSize = sp(sp.coerceIn(10f, 48f))
        rebuildLayout()
        restartMarqueeIfNeeded()
        invalidate()
    }

    fun setTypefaceStyle(bold: Boolean) {
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        rebuildLayout()
        restartMarqueeIfNeeded()
        invalidate()
    }

    /**
     * Queue depth for catch-up (captions waiting behind this line).
     * 0 clears catch-up; >0 shortens dwell and is used with [speedPxPerSec].
     */
    fun setCatchUpDepth(depth: Int) {
        catchUpDepth = depth.coerceAtLeast(0)
    }

    /**
     * Set caption text. In marquee mode restarts the scroll and will call
     * [onScrollFinished] once when a full cycle completes (or after dwell if no scroll).
     *
     * Same text is a no-op (keeps mid-scroll progress) so a late translation update
     * on the sibling line does not restart this line.
     */
    fun setCaptionText(value: String) {
        val t = value
        if (t == text) {
            invalidate()
            return
        }
        text = t
        finishPosted = false
        dwelling = false
        removeCallbacks(dwellRunnable)
        removeCallbacks(deferredSpeedRunnable)
        stopAnim()
        marqueeDistance = 0f
        rebuildLayout()
        if (mode == Mode.Marquee) {
            startMarquee()
        } else {
            invalidate()
        }
    }

    fun cancelScroll() {
        stopAnim()
        removeCallbacks(dwellRunnable)
        removeCallbacks(deferredSpeedRunnable)
        dwelling = false
        scrollOffset = 0f
        marqueeDistance = 0f
        finishPosted = true
        invalidate()
    }

    /**
     * Raise (or lower) marquee speed **while the current line is still running**,
     * without restarting from offset 0. Used to catch up when the next sentence
     * has already been recognized.
     *
     * Always stores [speedPxPerSec] so a later [setCaptionText] / layout pass
     * starts at the boosted rate (empty sibling lines used to drop the boost).
     *
     * - Scrolling: rebuild animator from [scrollOffset] with the new speed.
     * - Dwelling (text fits): shorten remaining dwell; deep backlog finishes ASAP.
     * - Not laid out yet: defer one frame and retry.
     * - Already finished: speed stored only.
     */
    fun applySpeedNow(newSpeedPxPerSec: Float) {
        if (mode != Mode.Marquee) return
        val target = newSpeedPxPerSec.coerceIn(MIN_SPEED, CATCH_UP_MAX_SPEED)
        val speedUnchanged = kotlin.math.abs(target - speedPxPerSec) < 0.5f
        // Always store so empty sibling / deferred start inherit the boost.
        speedPxPerSec = target
        if (finishPosted || text.isEmpty()) return

        if (dwelling) {
            // Already running catch-up dwell at this speed — do not reschedule
            // (onState re-fires backlog often and would push finish forever).
            if (speedUnchanged) return
            removeCallbacks(dwellRunnable)
            val dwellMs = dwellMsForCatchUp(catchUpDepth, target)
            if (dwellMs <= 0L) {
                dwelling = false
                if (!finishPosted) {
                    finishPosted = true
                    onScrollFinished?.invoke()
                }
            } else {
                postDelayed(dwellRunnable, dwellMs)
            }
            return
        }

        val anim = animator
        if (anim == null) {
            // Layout/start still pending — keep boosted speed and retry once measured.
            removeCallbacks(deferredSpeedRunnable)
            post(deferredSpeedRunnable)
            return
        }
        // Already animating at this speed — leave progress alone.
        if (speedUnchanged) return

        val current = scrollOffset
        val end = marqueeDistance
        val remaining = (end - current).coerceAtLeast(0f)
        if (remaining <= 1f) return

        // Cancel without firing finish; continue from current offset
        anim.removeAllListeners()
        anim.cancel()
        animator = null

        val durationMs = max(
            CATCH_UP_MIN_DURATION_MS,
            ceil(remaining / target * 1000.0).toLong()
        )
        animator = ValueAnimator.ofFloat(current, end).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                scrollOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!finishPosted) {
                        finishPosted = true
                        onScrollFinished?.invoke()
                    }
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = when (mode) {
            Mode.FullSentence -> {
                rebuildLayout(widthOverride = w - paddingLeft - paddingRight)
                val lh = staticLayout?.height ?: textPaint.fontMetricsInt.let { it.bottom - it.top }
                max(suggestedMinimumHeight, lh + paddingTop + paddingBottom)
            }
            Mode.Marquee -> {
                val fm = textPaint.fontMetricsInt
                val line = fm.bottom - fm.top
                max(suggestedMinimumHeight, line + paddingTop + paddingBottom)
            }
        }
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
        if (mode == Mode.Marquee && text.isNotEmpty() && !finishPosted) {
            restartMarqueeIfNeeded()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return
        val contentW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        when (mode) {
            Mode.FullSentence -> {
                val layout = staticLayout ?: return
                canvas.save()
                canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
            Mode.Marquee -> {
                val fm = textPaint.fontMetrics
                val baseline = paddingTop - fm.top
                val y = baseline
                canvas.save()
                canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
                if (textWidthPx <= contentW) {
                    val x = paddingLeft + (contentW - textWidthPx) / 2f
                    canvas.drawText(text, x, y, textPaint)
                } else {
                    canvas.drawText(text, paddingLeft - scrollOffset, y, textPaint)
                }
                canvas.restore()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        removeCallbacks(dwellRunnable)
        removeCallbacks(deferredSpeedRunnable)
        dwelling = false
        super.onDetachedFromWindow()
    }

    private fun rebuildLayout(widthOverride: Int = -1) {
        val contentW = if (widthOverride > 0) {
            widthOverride
        } else {
            (width - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        textWidthPx = if (text.isEmpty()) 0f else textPaint.measureText(text)
        if (mode == Mode.FullSentence) {
            val w = contentW.coerceAtLeast(1)
            staticLayout = StaticLayout.Builder
                .obtain(text.ifEmpty { " " }, 0, text.length.coerceAtLeast(1), textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setMaxLines(8)
                .build()
        } else {
            staticLayout = null
        }
    }

    private fun startMarquee() {
        stopAnim()
        removeCallbacks(dwellRunnable)
        removeCallbacks(deferredSpeedRunnable)
        dwelling = false
        scrollOffset = 0f
        marqueeDistance = 0f
        finishPosted = false
        // Empty text: idle, never spin waiting for layout (clear-to-blank path).
        if (text.isEmpty()) {
            finishPosted = true
            invalidate()
            return
        }
        val contentW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (contentW <= 0) {
            invalidate()
            // Wait for layout then try again
            post {
                if (mode == Mode.Marquee && !finishPosted && text.isNotEmpty()) startMarquee()
            }
            return
        }
        val travel = marqueeTravelPx(textWidthPx, contentW.toFloat())
        if (travel <= 0f) {
            // Fits entirely: dwell then finished (no scroll)
            invalidate()
            dwelling = true
            val dwellMs = dwellMsForCatchUp(catchUpDepth, speedPxPerSec)
            if (dwellMs <= 0L) {
                dwelling = false
                finishPosted = true
                onScrollFinished?.invoke()
            } else {
                postDelayed(dwellRunnable, dwellMs)
            }
            return
        }
        // Travel: start left-aligned (offset 0), stop when last glyph is fully
        // visible at the right content edge (offset = textWidth - contentW).
        marqueeDistance = travel
        val speed = speedPxPerSec.coerceIn(MIN_SPEED, CATCH_UP_MAX_SPEED)
        val minDur = if (catchUpDepth > 0) CATCH_UP_MIN_DURATION_MS else MIN_DURATION_MS
        val durationMs = max(minDur, ceil(travel / speed * 1000.0).toLong())
        animator = ValueAnimator.ofFloat(0f, travel).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                scrollOffset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!finishPosted) {
                        finishPosted = true
                        onScrollFinished?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun restartMarqueeIfNeeded() {
        if (mode != Mode.Marquee || text.isEmpty() || finishPosted) return
        startMarquee()
    }

    private fun stopAnim() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        const val DEFAULT_SPEED_PX_S = 60f
        private const val MIN_SPEED = 20f
        /** User-facing settings cap (also used when restoring base speed). */
        const val SETTINGS_MAX_SPEED = 160f
        /** Temporary cap while catching up a backlog of queued sentences. */
        const val CATCH_UP_MAX_SPEED = 480f
        private const val MIN_DURATION_MS = 800L
        /** Allow shorter remaining duration when speeding up mid-scroll. */
        private const val CATCH_UP_MIN_DURATION_MS = 200L
        private const val DWELL_MS = 1600L
        /** Shortened dwell while catch-up is active (depth 1–2). */
        private const val CATCH_UP_DWELL_MS = 350L

        /**
         * Horizontal travel for a marquee line.
         *
         * offset 0 → text left-aligned (start visible).
         * offset [return] → last glyph flush with the right content edge.
         * 0 when the text fits without scrolling.
         */
        fun marqueeTravelPx(textWidthPx: Float, contentWidthPx: Float): Float {
            if (textWidthPx <= 0f || contentWidthPx <= 0f) return 0f
            return (textWidthPx - contentWidthPx).coerceAtLeast(0f)
        }

        /**
         * Dwell (ms) before finishing a non-scrolling (fits) line.
         * Catch-up shortens or skips the end interval.
         */
        fun dwellMsForCatchUp(catchUpDepth: Int, speedPxPerSec: Float): Long {
            if (catchUpDepth <= 0) return DWELL_MS
            // Deep backlog or already at high catch-up speed → finish immediately
            if (catchUpDepth >= 3 || speedPxPerSec >= CATCH_UP_FINISH_DWELL_SPEED) return 0L
            return CATCH_UP_DWELL_MS
        }

        /**
         * At or above this catch-up speed, a dwelling line finishes immediately
         * (deep backlog).
         */
        private const val CATCH_UP_FINISH_DWELL_SPEED = 200f
    }
}
