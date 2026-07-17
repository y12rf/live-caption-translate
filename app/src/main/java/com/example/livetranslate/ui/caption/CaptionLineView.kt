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
 * Supports mid-scroll catch-up via [applySpeedNow]: when the next sentence is already
 * queued, the overlay can raise speed so the current line finishes sooner.
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
     * Set caption text. In marquee mode always restarts the scroll and will call
     * [onScrollFinished] once when a full cycle completes (or after dwell if no scroll).
     */
    fun setCaptionText(value: String) {
        val t = value
        if (t == text && mode == Mode.FullSentence) {
            invalidate()
            return
        }
        text = t
        finishPosted = false
        dwelling = false
        removeCallbacks(dwellRunnable)
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
     * - Scrolling: rebuild animator from [scrollOffset] with the new speed.
     * - Dwelling (text fits): shorten remaining dwell; high speed finishes immediately.
     * - Already finished: no-op.
     */
    fun applySpeedNow(newSpeedPxPerSec: Float) {
        if (mode != Mode.Marquee || finishPosted || text.isEmpty()) return
        val target = newSpeedPxPerSec.coerceIn(MIN_SPEED, CATCH_UP_MAX_SPEED)
        // Ignore tiny changes to avoid thrashing animators
        if (kotlin.math.abs(target - speedPxPerSec) < 0.5f && animator != null) return
        speedPxPerSec = target

        if (dwelling) {
            removeCallbacks(dwellRunnable)
            // Aggressive catch-up: skip remaining dwell so queue can advance
            if (target >= CATCH_UP_FINISH_DWELL_SPEED) {
                dwelling = false
                if (!finishPosted) {
                    finishPosted = true
                    onScrollFinished?.invoke()
                }
            } else {
                postDelayed(dwellRunnable, CATCH_UP_DWELL_MS)
            }
            return
        }

        val anim = animator ?: return
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
        dwelling = false
        scrollOffset = 0f
        marqueeDistance = 0f
        finishPosted = false
        val contentW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (contentW <= 0 || text.isEmpty()) {
            invalidate()
            // Wait for layout then try again
            post {
                if (mode == Mode.Marquee && !finishPosted) startMarquee()
            }
            return
        }
        if (textWidthPx <= contentW) {
            // Fits: dwell then finished (no scroll)
            invalidate()
            dwelling = true
            postDelayed(dwellRunnable, DWELL_MS)
            return
        }
        // Travel: start with text left-aligned (offset 0), scroll until last char exits left
        // plus a small gap so the end is readable.
        val gap = contentW * 0.35f
        val distance = textWidthPx + gap
        marqueeDistance = distance
        val speed = speedPxPerSec.coerceIn(MIN_SPEED, CATCH_UP_MAX_SPEED)
        val durationMs = max(MIN_DURATION_MS, ceil(distance / speed * 1000.0).toLong())
        animator = ValueAnimator.ofFloat(0f, distance).apply {
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
        /** Shortened dwell while catch-up is active. */
        private const val CATCH_UP_DWELL_MS = 350L
        /**
         * At or above this catch-up speed, a dwelling line finishes immediately
         * (deep backlog).
         */
        private const val CATCH_UP_FINISH_DWELL_SPEED = 200f
    }
}
