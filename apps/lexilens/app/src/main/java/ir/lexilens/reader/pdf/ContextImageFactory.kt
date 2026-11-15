package ir.lexilens.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.util.UUID

data class CropResult(
    val path: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object ContextImageFactory {

    /**
     * Builds a readable image of the exact sentence that is spoken on the
     * review card. Instead of cropping only the PDF line that contains the
     * word, it takes the sentence's piece from every involved line, stacks the
     * pieces and keeps the word highlighted. If the sentence cannot be mapped
     * back to the page exactly, it returns null and the caller shows the plain
     * text instead, so the image can never disagree with the audio.
     */
    fun create(
        context: Context,
        renderer: PageRenderer,
        pageText: PageText,
        pageIndex: Int,
        word: WordBox,
        sentence: String,
        targetWidth: Int = 2400,
    ): CropResult? {
        val span = pageText.sentenceSpanFor(word) ?: return null
        if (normalize(span.text) != normalize(sentence)) return null
        val shown = sentence.trim()
        if (shown.startsWith("…") || shown.endsWith("…")) return null
        val page = renderer.renderFresh(pageIndex, targetWidth) ?: return null
        try {
            data class Strip(val src: Rect, val dstY: Int)
            val strips = mutableListOf<Strip>()
            var totalHeight = 0
            var maxWidth = 0
            var wordStrip: Strip? = null
            var wordRect: RectF? = null

            for (slice in span.slices) {
                val line = pageText.lines.getOrNull(slice.line) ?: continue
                val first = pageText.words.getOrNull(slice.firstWord) ?: continue
                val last = pageText.words.getOrNull(slice.lastWord) ?: continue
                val padX = (line.h * 0.55f).coerceAtLeast(4f)
                val padY = (line.h * 0.55f).coerceAtLeast(4f)
                // When the sentence starts/ends in the middle of a PDF line,
                // only take a hair of padding. A full pad would pull a period
                // or the first letter of the neighbouring sentence into the
                // picture, which is exactly the mismatch this crop avoids.
                val startsMidLine = slice.firstWord > line.wordStart
                val endsMidLine = slice.lastWord < line.wordEnd
                val leftPad = if (startsMidLine) 1f else padX
                val rightPad = if (endsMidLine) 1f else padX
                val x0 = (first.x - leftPad).coerceAtLeast(0f)
                val x1 = (last.right + rightPad).coerceAtMost(pageText.width)
                if (x1 <= x0) continue
                val y0 = (line.y - padY).coerceAtLeast(0f)
                val y1 = (line.bottom + padY).coerceAtMost(pageText.height)
                if (y1 <= y0) continue
                val mapped = mapRect(x0, y0, x1 - x0, y1 - y0, pageText, page)
                val src = Rect(
                    mapped.left.toInt().coerceAtLeast(0),
                    mapped.top.toInt().coerceAtLeast(0),
                    mapped.right.toInt().coerceAtMost(page.width),
                    mapped.bottom.toInt().coerceAtMost(page.height),
                )
                if (src.width() < 2 || src.height() < 2) continue
                val gap = if (strips.isEmpty()) 0 else (line.h * 0.2f * (page.width / pageText.width.coerceAtLeast(1f)))
                    .toInt().coerceAtLeast(2)
                val dstY = totalHeight + gap
                val strip = Strip(src, dstY)
                strips.add(strip)
                totalHeight = dstY + src.height()
                maxWidth = maxOf(maxWidth, src.width())
                if (slice.line == word.line) {
                    wordStrip = strip
                    wordRect = mapRect(word.x, word.y, word.w, word.h, pageText, page)
                }
            }
            if (strips.isEmpty() || totalHeight < 2 || maxWidth < 2) return null
            val composite = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (strip in strips) {
                val dst = Rect(0, strip.dstY, strip.src.width(), strip.dstY + strip.src.height())
                canvas.drawBitmap(page, strip.src, dst, paint)
            }
            var l = 0f
            var t = 0f
            var r = 1f
            var b = 1f
            val wr = wordRect
            val ws = wordStrip
            if (wr != null && ws != null) {
                val localLeft = wr.left - ws.src.left
                val localTop = wr.top - ws.src.top + ws.dstY
                l = (localLeft / maxWidth).coerceIn(0f, 1f)
                t = (localTop / totalHeight).coerceIn(0f, 1f)
                r = ((localLeft + wr.width()) / maxWidth).coerceIn(0f, 1f)
                b = ((localTop + wr.height()) / totalHeight).coerceIn(0f, 1f)
            }
            val dir = File(context.filesDir, "crops").apply { mkdirs() }
            val file = File(dir, "crop_${UUID.randomUUID()}.png")
            file.outputStream().buffered().use { out ->
                composite.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            composite.recycle()
            return CropResult(file.absolutePath, l, t, r, b)
        } finally {
            if (!page.isRecycled) page.recycle()
        }
    }

    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private fun mapRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: PageText,
        page: Bitmap,
    ): RectF {
        val rot = ((text.rotation % 360) + 360) % 360
        val sx = if (rot == 90 || rot == 270) page.width / text.height.coerceAtLeast(1f) else page.width / text.width.coerceAtLeast(1f)
        val sy = if (rot == 90 || rot == 270) page.height / text.width.coerceAtLeast(1f) else page.height / text.height.coerceAtLeast(1f)
        return when (rot) {
            90 -> {
                val left = text.height - (y + h)
                val top = x
                RectF(left * sx, top * sy, (left + h) * sx, (top + w) * sy)
            }
            180 -> {
                val left = text.width - (x + w)
                val top = text.height - (y + h)
                RectF(left * sx, top * sy, (left + w) * sx, (top + h) * sy)
            }
            270 -> {
                val left = y
                val top = text.width - (x + w)
                RectF(left * sx, top * sy, (left + h) * sx, (top + w) * sy)
            }
            else -> RectF(x * sx, y * sy, (x + w) * sx, (y + h) * sy)
        }
    }
}
