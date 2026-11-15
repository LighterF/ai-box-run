package ir.lexilens.reader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.OverScroller
import ir.lexilens.reader.pdf.ReflowContent
import ir.lexilens.reader.pdf.ReflowParagraph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a reflowed PDF page as continuous, scrollable paragraphs. It owns no
 * PDF state; the activity feeds it [ReflowContent] for the current page.
 *
 * The view also hit-tests words so the existing long-press lookup, voice and
 * card creation keep working in reflow mode.
 */
class ReflowPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onWordLongPress(wordIndex: Int, viewX: Float, viewY: Float)
        fun onLongPressMiss()
        fun onTap()
    }

    var listener: Listener? = null

    var pageBackground: Int = Color.rgb(246, 244, 237)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var pageTextColor: Int = Color.rgb(20, 35, 30)
        set(value) {
            if (field == value) return
            field = value
            paint.color = value
            emptyPaint.color = value
            invalidate()
        }

    var textSizePercent: Int = 100
        set(value) {
            val clamped = value.coerceIn(MIN_TEXT_PERCENT, MAX_TEXT_PERCENT)
            if (field == clamped) return
            field = clamped
            rebuild(keepScrollRatio = true)
        }

    /** Base face for reflowed text; by default it mirrors the PDF's own font. */
    var textTypeface: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        set(value) {
            if (field == value) return
            field = value
            paint.typeface = value
            emptyPaint.typeface = value
            rebuild(keepScrollRatio = true)
        }

    /**
     * Real italic face when the PDF embeds one; when null the view slants the
     * base face itself so italic words still stand out from the text.
     */
    var italicTypeface: Typeface? = null
        set(value) {
            if (field == value) return
            field = value
            rebuild(keepScrollRatio = true)
        }

    var content: ReflowContent = ReflowContent.EMPTY
        private set

    var emptyText: String = "این صفحه متن قابل بازچینش ندارد؛ شاید اسکن‌شده باشد."
        set(value) {
            field = value
            emptyLayout = null
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val overscrollDistance = 58f * density
    private val hitSlop = 14f * density
    private val minFlingVelocity = 120f
    private val horizontalPadding = 22f * density
    private val fullTopPadding = 84f * density
    private val fullBottomPadding = 132f * density
    private val compactTopPadding = 30f * density
    private val compactBottomPadding = 44f * density
    private var topPadding = fullTopPadding
    private var bottomPadding = fullBottomPadding

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        color = pageTextColor
        textSize = 17f * scaledDensity
    }
    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        color = pageTextColor
        textSize = 15f * scaledDensity
    }

    private val layouts = ArrayList<StaticLayout>()
    private val paragraphTops = ArrayList<Float>()

    /** Drawn rectangle of every word, used for pixel-accurate long-press hits. */
    private val wordHits = ArrayList<WordHit>()

    private var emptyLayout: StaticLayout? = null
    private var emptyLayoutWidth = 0
    private var contentBottom = 0f
    private var maxScroll = 0
    private var scrollY = 0f
    private var lastWidth = 0
    private var lastHeight = 0

    private val scroller = OverScroller(context)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragAnchorY = 0f
    private var dragAnchorScroll = 0f
    private var dragging = false
    private var longPressFired = false
    private var velocityTracker: VelocityTracker? = null

    private val longPressRunnable = Runnable {
        if (dragging || content.isEmpty) return@Runnable
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val index = wordIndexAt(downX, downY)
        if (index != null) {
            listener?.onWordLongPress(index, downX, downY)
        } else {
            listener?.onLongPressMiss()
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setContent(value: ReflowContent) {
        content = value
        emptyText = if (value.isEmpty) {
            "این صفحه متن قابل بازچینش ندارد؛ شاید اسکن‌شده باشد."
        } else {
            ""
        }
        emptyLayout = null
        scrollY = 0f
        scroller.forceFinished(true)
        rebuild(keepScrollRatio = false)
    }

    fun showLoading(message: String = "در حال آماده‌سازی متن صفحه…") {
        content = ReflowContent.EMPTY
        emptyText = message
        emptyLayout = null
        scrollY = 0f
        scroller.forceFinished(true)
        rebuild(keepScrollRatio = false)
    }

    fun showUnavailable() {
        content = ReflowContent.EMPTY
        emptyText = "این صفحه متن قابل بازچینش ندارد؛ شاید اسکن‌شده باشد."
        emptyLayout = null
        scrollY = 0f
        scroller.forceFinished(true)
        rebuild(keepScrollRatio = false)
    }

    /** Leaves room for the floating bars, or reclaims it when they hide. */
    fun setChromeVisible(visible: Boolean) {
        val newTop = if (visible) fullTopPadding else compactTopPadding
        val newBottom = if (visible) fullBottomPadding else compactBottomPadding
        if (newTop == topPadding && newBottom == bottomPadding) return
        topPadding = newTop
        bottomPadding = newBottom
        rebuild(keepScrollRatio = true)
    }

    fun scrollToTop() {
        scroller.forceFinished(true)
        scrollY = 0f
        invalidate()
    }

    /** Current vertical offset in content space; negative/over-max while overscrolled. */
    internal fun currentScrollOffset(): Float = scrollY

    /** Exposed for tests and for the activity's diagnostics. */
    fun wordIndexAt(viewX: Float, viewY: Float): Int? {
        if (wordHits.isEmpty()) return null
        val x = viewX - horizontalPadding
        val y = viewY + scrollY - topPadding
        var best: WordHit? = null
        var bestDistance = Float.MAX_VALUE
        for (hit in wordHits) {
            val dx = when {
                x < hit.left -> hit.left - x
                x > hit.right -> x - hit.right
                else -> 0f
            }
            val dy = when {
                y < hit.top -> hit.top - y
                y > hit.bottom -> y - hit.bottom
                else -> 0f
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = hit
            }
        }
        val slop = hitSlop * hitSlop
        return if (best != null && bestDistance <= slop) best.wordIndex else null
    }

    private fun rebuild(keepScrollRatio: Boolean) {
        val ratio = if (keepScrollRatio && maxScroll > 0) scrollY / maxScroll else 0f
        layouts.clear()
        paragraphTops.clear()
        emptyLayout = null
        emptyLayoutWidth = 0
        val width = width
        val height = height
        if (width <= 0) {
            wordHits.clear()
            contentBottom = 0f
            maxScroll = 0
            scrollY = 0f
            return
        }
        val available = (width - 2f * horizontalPadding).coerceAtLeast(80f)
        val textSize = 17f * (textSizePercent / 100f) * scaledDensity
        paint.textSize = textSize
        val paragraphGap = textSize * 0.72f
        var y = 0f
        if (content.isEmpty) {
            val message = emptyText
            if (message.isNotBlank()) {
                emptyPaint.textSize = 15f * scaledDensity
                emptyLayout = StaticLayout.Builder
                    .obtain(message, 0, message.length, emptyPaint, available.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.35f)
                    .setIncludePad(false)
                    .build()
                emptyLayoutWidth = available.toInt()
            }
        } else {
            for (paragraph in content.paragraphs) {
                val styled = styledParagraph(paragraph)
                val layout = StaticLayout.Builder
                    .obtain(styled, 0, styled.length, paint, available.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.22f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                    .build()
                paragraphTops.add(y)
                layouts.add(layout)
                y += layout.height + paragraphGap
            }
            if (layouts.isNotEmpty()) y -= paragraphGap
        }
        contentBottom = topPadding + y + bottomPadding
        maxScroll = max(0, (contentBottom - height).toInt())
        scrollY = if (keepScrollRatio) {
            ratio * maxScroll
        } else {
            0f
        }
        scrollY = scrollY.coerceIn(0f, maxScroll.toFloat())
        buildWordHits()
        lastWidth = width
        lastHeight = height
    }

    /**
     * Rebuilds the paragraph with italic spans where the PDF itself used an
     * italic face, so "I want" keeps only its "I" slanted.
     */
    private fun styledParagraph(paragraph: ReflowParagraph): CharSequence {
        if (paragraph.words.none { it.italic }) return paragraph.text
        val styled = SpannableString(paragraph.text)
        for (span in paragraph.words) {
            if (!span.italic) continue
            val start = span.start.coerceIn(0, styled.length)
            val end = span.end.coerceIn(start, styled.length)
            if (end <= start) continue
            styled.setSpan(
                ReflowItalicSpan(italicTypeface),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return styled
    }

    /** Records where each word is actually painted so hits never guess. */
    private fun buildWordHits() {
        wordHits.clear()
        if (layouts.isEmpty()) return
        for (i in layouts.indices) {
            val layout = layouts[i]
            val paragraph = content.paragraphs.getOrNull(i) ?: continue
            val paragraphTop = paragraphTops[i]
            for (span in paragraph.words) {
                val start = span.start.coerceIn(0, layout.text.length)
                val end = span.end.coerceIn(start, layout.text.length)
                if (end <= start) continue
                val firstLine = layout.getLineForOffset(start)
                val lastLine = layout.getLineForOffset((end - 1).coerceAtLeast(0))
                for (line in firstLine..lastLine) {
                    val lineStart = layout.getLineStart(line)
                    val lineEnd = layout.getLineEnd(line)
                    val from = max(start, lineStart)
                    val to = min(end, lineEnd)
                    if (to <= from) continue
                    val left = min(
                        layout.getPrimaryHorizontal(from),
                        layout.getSecondaryHorizontal(from),
                    )
                    val right = max(
                        layout.getPrimaryHorizontal(to),
                        layout.getSecondaryHorizontal(to),
                    )
                    if (right - left < 1f) continue
                    wordHits.add(
                        WordHit(
                            wordIndex = span.wordIndex,
                            left = left,
                            right = right,
                            top = paragraphTop + layout.getLineTop(line),
                            bottom = paragraphTop + layout.getLineBottom(line),
                        )
                    )
                }
            }
        }
    }

    /** Content-space drag, rubber-banded past the ends instead of hard-stopping. */
    private fun scrollWithFinger(fingerY: Float) {
        scrollY = rubberBand(dragAnchorScroll + (dragAnchorY - fingerY))
        invalidate()
    }

    private fun rubberBand(raw: Float): Float {
        val max = maxScroll.toFloat()
        if (max <= 0f) return 0f
        return when {
            raw < 0f -> -dampen(-raw)
            raw > max -> max + dampen(raw - max)
            else -> raw
        }.coerceIn(-overscrollDistance, max + overscrollDistance)
    }

    private fun dampen(distance: Float): Float =
        overscrollDistance * (1f - 1f / (distance / overscrollDistance * 0.6f + 1f))

    private fun settle() {
        if (scrollY < 0f || scrollY > maxScroll) {
            scroller.springBack(0, scrollY.toInt(), 0, 0, 0, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth || h != lastHeight) rebuild(keepScrollRatio = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(pageBackground)
        if (layouts.isEmpty()) {
            val layout = emptyLayout ?: return
            val cx = (width - layout.width) / 2f
            val cy = (height - layout.height) / 2f
            canvas.save()
            canvas.translate(cx, cy)
            layout.draw(canvas)
            canvas.restore()
            return
        }
        canvas.save()
        canvas.translate(horizontalPadding, topPadding - scrollY)
        for (i in layouts.indices) {
            canvas.save()
            canvas.translate(0f, paragraphTops[i])
            layouts[i].draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                downX = event.x
                downY = event.y
                dragAnchorY = event.y
                dragAnchorScroll = scrollY
                dragging = false
                if (scrollY < 0f || scrollY > maxScroll) settle()
                longPressFired = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                handler.removeCallbacks(longPressRunnable)
                if (!content.isEmpty) {
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!dragging) {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                }
                if (dragging) {
                    scrollWithFinger(event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                if (wasDragging) {
                    if (scrollY < 0f || scrollY > maxScroll) {
                        settle()
                    } else {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = velocityTracker?.yVelocity ?: 0f
                        val maxVelocity = ViewConfiguration.get(context)
                            .scaledMaximumFlingVelocity.toFloat()
                        val clampedVy = vy.coerceIn(-maxVelocity, maxVelocity)
                        if (abs(clampedVy) > minFlingVelocity) {
                            scroller.fling(
                                0,
                                scrollY.toInt(),
                                0,
                                -clampedVy.toInt(),
                                0,
                                0,
                                0,
                                maxScroll,
                            )
                        }
                    }
                } else if (!longPressFired) {
                    listener?.onTap()
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (scrollY < 0f || scrollY > maxScroll) settle()
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private data class WordHit(
        val wordIndex: Int,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
    )

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    companion object {
        const val MIN_TEXT_PERCENT = 65
        const val MAX_TEXT_PERCENT = 260
    }
}
