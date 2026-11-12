package ir.lexilens.reader.pdf

import android.content.Context
import android.graphics.Bitmap
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

    fun create(
        context: Context,
        renderer: PageRenderer,
        pageText: PageText,
        pageIndex: Int,
        word: WordBox,
        targetWidth: Int = 2400,
    ): CropResult? {
        val line = pageText.lines.getOrNull(word.line) ?: return null
        val page = renderer.renderFresh(pageIndex, targetWidth) ?: return null
        try {
            val lineRect = mapRect(line.x, line.y, line.w, line.h, pageText, page)
            val wordRect = mapRect(word.x, word.y, word.w, word.h, pageText, page)

            val padX = (lineRect.height() * 1.2f).coerceAtLeast(8f)
            val padTop = (lineRect.height() * 0.8f).coerceAtLeast(6f)
            val padBottom = (lineRect.height() * 2.2f).coerceAtLeast(10f)
            var x0 = (lineRect.left - padX).toInt().coerceAtLeast(0)
            var y0 = (lineRect.top - padTop).toInt().coerceAtLeast(0)
            var x1 = (lineRect.left + lineRect.width() + padX).toInt().coerceAtMost(page.width)
            var y1 = (lineRect.top + lineRect.height() + padBottom).toInt().coerceAtMost(page.height)

            // Give very short lines a wider, more readable crop.
            val minWidth = (page.width * 0.28f).toInt()
            if (x1 - x0 < minWidth) {
                val center = (x0 + x1) / 2
                x0 = (center - minWidth / 2).coerceAtLeast(0)
                x1 = (x0 + minWidth).coerceAtMost(page.width)
                if (x1 - x0 < minWidth) x0 = (x1 - minWidth).coerceAtLeast(0)
            }
            if (x1 <= x0 || y1 <= y0) return null

            val cropW = x1 - x0
            val cropH = y1 - y0
            val crop = Bitmap.createBitmap(page, x0, y0, cropW, cropH)
            val dir = File(context.filesDir, "crops").apply { mkdirs() }
            val file = File(dir, "crop_${UUID.randomUUID()}.png")
            file.outputStream().buffered().use { out ->
                crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            crop.recycle()

            val wl = ((wordRect.left - x0) / cropW).coerceIn(0f, 1f)
            val wt = ((wordRect.top - y0) / cropH).coerceIn(0f, 1f)
            val wr = ((wordRect.left + wordRect.width() - x0) / cropW).coerceIn(0f, 1f)
            val wb = ((wordRect.top + wordRect.height() - y0) / cropH).coerceIn(0f, 1f)
            return CropResult(file.absolutePath, wl, wt, wr, wb)
        } finally {
            if (!page.isRecycled) page.recycle()
        }
    }

    private fun mapRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: PageText,
        page: Bitmap,
    ): android.graphics.RectF {
        val rot = ((text.rotation % 360) + 360) % 360
        val sx = if (rot == 90 || rot == 270) page.width / text.height.coerceAtLeast(1f) else page.width / text.width.coerceAtLeast(1f)
        val sy = if (rot == 90 || rot == 270) page.height / text.width.coerceAtLeast(1f) else page.height / text.height.coerceAtLeast(1f)
        return when (rot) {
            90 -> {
                val left = text.height - (y + h)
                val top = x
                android.graphics.RectF(left * sx, top * sy, (left + h) * sx, (top + w) * sy)
            }
            180 -> {
                val left = text.width - (x + w)
                val top = text.height - (y + h)
                android.graphics.RectF(left * sx, top * sy, (left + w) * sx, (top + h) * sy)
            }
            270 -> {
                val left = y
                val top = text.width - (x + w)
                android.graphics.RectF(left * sx, top * sy, (left + h) * sx, (top + w) * sy)
            }
            else -> android.graphics.RectF(x * sx, y * sy, (x + w) * sx, (y + h) * sy)
        }
    }
}
