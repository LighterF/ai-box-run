package ir.lexilens.reader.review

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import ir.lexilens.reader.util.TextUtils
import java.io.File
import kotlin.math.min

class ContextHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var hl = RectF(0f, 0f, 1f, 1f)
    private var word = ""
    private var line = ""
    private var textLayout: StaticLayout? = null

    private var progress = 0f
    private var sweep: ValueAnimator? = null
    private var pulse: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(40, 0, 0, 0)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 158, 11) }
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(217, 119, 6)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(16f)
        color = Color.rgb(28, 37, 34)
    }
    private val radius = dp(12f)
    private val clipPath = Path()
    private val paperBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 245, 238) }

    fun setCrop(path: String, l: Float, t: Float, r: Float, b: Float, word: String) {
        cancelAnimations()
        this.word = word
        line = ""
        textLayout = null
        bitmap?.recycle()
        bitmap = runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(File(path).absolutePath, opts)
        }.getOrNull()
        hl = RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (hl.right <= hl.left) hl.right = (hl.left + 0.12f).coerceAtMost(1f)
        if (hl.bottom <= hl.top) hl.bottom = (hl.top + 0.35f).coerceAtMost(1f)
        progress = 0f
        requestLayout()
        invalidate()
        startSweep()
    }

    fun setText(line: String, word: String) {
        cancelAnimations()
        bitmap?.recycle()
        bitmap = null
        this.line = line
        this.word = word
        textLayout = null
        progress = 0f
        requestLayout()
        invalidate()
        startSweep()
    }

    private fun startSweep() {
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 850
        anim.interpolator = DecelerateInterpolator(1.6f)
        anim.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                startPulse()
            }
        })
        sweep = anim
        anim.start()
    }

    private fun startPulse() {
        val anim = ValueAnimator.ofFloat(0f, 1f, 0.35f)
        anim.duration = 2600
        anim.repeatCount = 2
        anim.addUpdateListener {
            progress = 1f
            pulseAlpha = it.animatedValue as Float
            invalidate()
        }
        pulse = anim
        anim.start()
    }

    private var pulseAlpha = 0.3f

    private fun cancelAnimations() {
        sweep?.cancel(); sweep = null
        pulse?.cancel(); pulse = null
        progress = 0f
        pulseAlpha = 0.3f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val bmp = bitmap
        val h = when {
            bmp != null -> (w.toFloat() * bmp.height / bmp.width).toInt().coerceIn(dp(68f).toInt(), dp(280f).toInt())
            else -> {
                val layout = buildTextLayout((w - dp(22f)).toInt())
                textLayout = layout
                (layout.height + dp(24f)).toInt().coerceAtLeast(dp(78f).toInt())
            }
        }
        setMeasuredDimension(w, h)
    }

    private fun buildTextLayout(avail: Int): StaticLayout {
        val safe = TextUtils.normalizeWord(line).ifEmpty { line }
        val source = if (safe.isEmpty()) "…" else line.ifEmpty { line }
        return StaticLayout.Builder.obtain(source, 0, source.length, textPaint, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dp(3f), 1f)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp != null) drawBitmapContext(canvas, bmp) else drawTextContext(canvas)
    }

    private fun drawBitmapContext(canvas: Canvas, bmp: Bitmap) {
        val pad = dp(1f)
        val dst = RectF(pad, pad, width - pad, height - pad)
        clipPath.reset()
        clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        // Fill letterbox areas with a soft paper tone.
        canvas.drawColor(Color.rgb(247, 245, 238))
        // Fit width, center vertically.
        val scale = width.toFloat() / bmp.width
        val drawH = bmp.height * scale
        val top = (height - drawH) / 2f
        val img = RectF(0f, top, width.toFloat(), top + drawH)
        canvas.drawBitmap(bmp, null, img, paint)

        val hr = RectF(
            img.left + hl.left * img.width(),
            img.top + hl.top * img.height(),
            img.left + hl.right * img.width(),
            img.top + hl.bottom * img.height(),
        )
        drawHighlight(canvas, hr)
        canvas.restore()
        canvas.drawRoundRect(dst, radius, radius, border)
    }

    private fun drawTextContext(canvas: Canvas) {
        val padX = dp(12f)
        val padY = dp(10f)
        val paper = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(paper, radius, radius, paperBg)
        val layout = textLayout ?: buildTextLayout((width - padX * 2).toInt()).also { textLayout = it }
        canvas.save()
        canvas.translate(padX, padY)

        val source = layout.text.toString()
        val wordIdx = source.indexOf(word, ignoreCase = true)
        val lineForWord = if (wordIdx >= 0) layout.getLineForOffset(wordIdx) else 0
        val lineStart = layout.getLineStart(lineForWord)
        val lineText = source.subSequence(lineStart, layout.getLineEnd(lineForWord)).toString()
        val idx = lineText.indexOf(word, ignoreCase = true).coerceAtLeast(0)
        val startX = layout.getPrimaryHorizontal(lineStart + idx)
        val endX = layout.getPrimaryHorizontal((lineStart + idx + word.length).coerceAtMost(source.length))
        val baseline = layout.getLineBaseline(lineForWord)
        val top = (baseline - textPaint.textSize * 1.15f)
        val hr = RectF(
            startX - dp(2f),
            top,
            endX + dp(2f),
            baseline + textPaint.textSize * 0.22f,
        )
        drawHighlight(canvas, hr)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, full: RectF) {
        if (full.width() <= 0f || full.height() <= 0f) return
        val p = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p)
        val hr = RectF(full.left, full.top, full.left + full.width() * eased, full.bottom)
        val baseAlpha = 120
        val alpha = if (p >= 1f) (baseAlpha * (0.55f + 0.35f * pulseAlpha)).toInt().coerceIn(45, 150) else baseAlpha
        highlight.alpha = alpha
        canvas.drawRoundRect(hr, dp(4f), dp(4f), highlight)
        if (p > 0.15f) {
            canvas.drawLine(
                hr.left + dp(2f),
                hr.bottom - dp(1.5f),
                (hr.left + hr.width() * 0.98f).coerceAtLeast(hr.left + dp(2f)),
                hr.bottom - dp(1.5f),
                underline
            )
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
