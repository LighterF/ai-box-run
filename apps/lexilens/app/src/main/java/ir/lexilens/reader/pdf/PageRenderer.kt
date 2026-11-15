package ir.lexilens.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File

class PageRenderer(private val file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = android.graphics.pdf.PdfRenderer(pfd)
    private val cache = object : LruCache<Int, Bitmap>(3) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val maxBytes = 48 * 1024 * 1024

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun pageSize(index: Int): Pair<Int, Int> {
        renderer.openPage(index).use { page ->
            return page.width to page.height
        }
    }

    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap {
        cache.get(index)?.let { return it }
        renderer.openPage(index).use { page ->
            val pw = page.width.coerceAtLeast(1)
            val ph = page.height.coerceAtLeast(1)
            val scale = targetWidth.toFloat() / pw
            var w = (pw * scale).toInt().coerceAtLeast(1)
            var h = (ph * scale).toInt().coerceAtLeast(1)
            // Keep memory bounded for very tall pages.
            val maxDim = 5200
            if (h > maxDim) {
                val k = maxDim.toFloat() / h
                w = (w * k).toInt().coerceAtLeast(1)
                h = maxDim
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (cache.size() + bitmap.byteCount > maxBytes) cache.evictAll()
            cache.put(index, bitmap)
            return bitmap
        }
    }

    @Synchronized
    fun renderFresh(index: Int, targetWidth: Int): Bitmap? {
        return runCatching {
            renderer.openPage(index).use { page ->
                val pw = page.width.coerceAtLeast(1)
                val ph = page.height.coerceAtLeast(1)
                val scale = targetWidth.toFloat() / pw
                var w = (pw * scale).toInt().coerceAtLeast(1)
                var h = (ph * scale).toInt().coerceAtLeast(1)
                val maxDim = 6400
                if (h > maxDim) {
                    val k = maxDim.toFloat() / h
                    w = (w * k).toInt().coerceAtLeast(1)
                    h = maxDim
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bitmap
            }
        }.getOrNull()
    }

    /**
     * Renders only a region of a page. The region is expressed in PDF points
     * (the same coordinate space used by the text layer), and [outWidth] x
     * [outHeight] is the requested raster size. This keeps zoomed text crisp
     * without allocating a huge full-page bitmap.
     */
    @Synchronized
    fun renderRegion(
        index: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap? = runCatching {
        val rw = (right - left).coerceAtLeast(0.5f)
        val rh = (bottom - top).coerceAtLeast(0.5f)
        val w = outWidth.coerceIn(1, MAX_REGION_DIM)
        val h = outHeight.coerceIn(1, MAX_REGION_DIM)
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val sx = w / rw
            val sy = h / rh
            val matrix = Matrix().apply {
                setScale(sx, sy)
                postTranslate(-left * sx, -top * sy)
            }
            page.render(
                bitmap,
                null,
                matrix,
                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
            )
            bitmap
        }
    }.getOrNull()

    @Synchronized
    fun evict(index: Int) {
        cache.remove(index)
    }

    private companion object {
        const val MAX_REGION_DIM = 4096
    }

    @Synchronized
    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
