package ir.lexilens.reader.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import java.io.File

class ContextHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var hl = RectF(0f, 0f, 1f, 1f)
    private var word = ""
    private var line = ""
    private var textLayout: StaticLayout? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(40, 0, 0, 0)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 226, 120)
        alpha = HIGHLIGHT_ALPHA
    }
    // Multiply keeps the black glyphs black while tinting only the paper.
    private val imageHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 226, 120)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(16f)
        color = Color.rgb(28, 37, 34)
    }
    private val radius = dp(12f)
    private val clipPath = Path()
    private val paperBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 245, 238) }

    fun setCrop(path: String, l: Float, t: Float, r: Float, b: Float, word: String) {
        bitmap?.recycle()
        this.word = word
        line = ""
        textLayout = null
        bitmap = runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(File(path).absolutePath, opts)
        }.getOrNull()
        hl = RectF(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (hl.right <= hl.left) hl.right = (hl.left + 0.12f).coerceAtMost(1f)
        if (hl.bottom <= hl.top) hl.bottom = (hl.top + 0.18f).coerceAtMost(1f)
        requestLayout()
        invalidate()
    }

    fun setText(line: String, word: String) {
        bitmap?.recycle()
        bitmap = null
        this.line = line
        this.word = word
        textLayout = null
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val bmp = bitmap
        val h = when {
            bmp != null -> (w.toFloat() * bmp.height / bmp.width).toInt().coerceIn(dp(48f).toInt(), dp(420f).toInt())
            else -> {
                val layout = buildTextLayout((w - dp(22f)).toInt())
                textLayout = layout
                (layout.height + dp(24f)).toInt().coerceAtLeast(dp(78f).toInt())
            }
        }
        setMeasuredDimension(w, h)
    }

    private fun buildTextLayout(avail: Int): StaticLayout {
        val source = if (line.isEmpty()) "…" else line
        return StaticLayout.Builder.obtain(source, 0, source.length, textPaint, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
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
        canvas.drawColor(Color.rgb(247, 245, 238))
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        val img = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bmp, null, img, paint)

        val hr = RectF(
            img.left + hl.left * img.width(),
            img.top + hl.top * img.height(),
            img.left + hl.right * img.width(),
            img.top + hl.bottom * img.height(),
        )
        drawHighlight(canvas, hr, imageHighlight)
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
        if (wordIdx >= 0 && word.isNotEmpty()) {
            val lineForWord = layout.getLineForOffset(wordIdx)
            val lineStart = layout.getLineStart(lineForWord)
            val lineEnd = layout.getLineEnd(lineForWord)
            val lineText = source.subSequence(lineStart, lineEnd).toString()
            val idx = lineText.indexOf(word, ignoreCase = true).coerceAtLeast(0)
            val start = (lineStart + idx).coerceIn(0, source.length)
            val end = (start + word.length).coerceIn(start, source.length)
            val startX = layout.getPrimaryHorizontal(start)
            val endX = layout.getPrimaryHorizontal(end)
            val top = layout.getLineTop(lineForWord).toFloat() + dp(1f)
            val bottom = layout.getLineBottom(lineForWord).toFloat() - dp(1f)
            if (endX > startX && bottom > top) {
                drawHighlight(
                    canvas,
                    RectF(startX - dp(2f), top, endX + dp(2f), bottom),
                    highlight,
                )
            }
        }
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, full: RectF, paint: Paint) {
        if (full.width() <= 0f || full.height() <= 0f) return
        canvas.drawRoundRect(full, dp(2.5f), dp(2.5f), paint)
    }

    override fun onDetachedFromWindow() {
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private companion object {
        const val HIGHLIGHT_ALPHA = 48
    }
}
