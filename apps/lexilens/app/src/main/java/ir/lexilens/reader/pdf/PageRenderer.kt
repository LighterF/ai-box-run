package ir.lexilens.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
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
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    @Synchronized
    fun evict(index: Int) {
        cache.remove(index)
    }

    @Synchronized
    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
