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
    /** Pixels per second when marquee is active. */
    var speedPxPerSec: Float = DEFAULT_SPEED_PX_S
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    var onScrollFinished: (() -> Unit)? = null

    private var finishPosted = false
    private val dwellRunnable = Runnable {
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
        removeCallbacks(dwellRunnable)
        stopAnim()
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
        scrollOffset = 0f
        finishPosted = true
        invalidate()
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
        scrollOffset = 0f
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
            postDelayed(dwellRunnable, DWELL_MS)
            return
        }
        // Travel: start with text left-aligned (offset 0), scroll until last char exits left
        // plus a small gap so the end is readable.
        val gap = contentW * 0.35f
        val distance = textWidthPx + gap
        val speed = speedPxPerSec.coerceIn(MIN_SPEED, MAX_SPEED)
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
        private const val MAX_SPEED = 160f
        private const val MIN_DURATION_MS = 800L
        private const val DWELL_MS = 1600L
    }
}
