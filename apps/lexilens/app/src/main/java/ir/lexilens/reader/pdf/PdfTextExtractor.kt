package ir.lexilens.reader.pdf

import android.content.Context
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.StringWriter

class PdfTextExtractor(
    private val context: Context,
    private val docId: Long,
    private val pdfFile: File,
) : Closeable {

    private var document: PDDocument? = null
    private val memory = object : LruCache<Int, PageText>(10) {
        override fun sizeOf(key: Int, value: PageText): Int = value.words.size.coerceAtLeast(1)
    }

    @Synchronized
    fun extract(pageIndex: Int): PageText? {
        memory.get(pageIndex)?.let { return it }
        val cached = readCache(pageIndex)
        if (cached != null) {
            memory.put(pageIndex, cached)
            return cached
        }
        return runCatching { extractNow(pageIndex) }.getOrElse { null }
    }

    private fun extractNow(pageIndex: Int): PageText? {
        val doc = document ?: PDDocument.load(pdfFile).also { document = it }
        val collector = CollectingStripper()
        collector.startPage = pageIndex + 1
        collector.endPage = pageIndex + 1
        collector.sortByPosition = false
        collector.writeText(doc, StringWriter())
        val pageText = collector.toPageText(doc, pageIndex)
        writeCache(pageIndex, pageText)
        memory.put(pageIndex, pageText)
        return pageText
    }

    private fun cacheFile(pageIndex: Int): File {
        val dir = File(File(context.filesDir, "text"), docId.toString())
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "page_$pageIndex.json")
    }

    private fun readCache(pageIndex: Int): PageText? {
        val f = cacheFile(pageIndex)
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(f.readText())
            if (json.optInt("v", 0) < 2) return null
            PageText.fromJson(json)
        }.getOrNull()
    }

    private fun writeCache(pageIndex: Int, pageText: PageText) {
        runCatching {
            val f = cacheFile(pageIndex)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(pageText.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    @Synchronized
    override fun close() {
        memory.evictAll()
        runCatching { document?.close() }
        document = null
    }

    private class CollectingStripper : PDFTextStripper() {
        private val positions = mutableListOf<TextPosition>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions != null) positions.addAll(textPositions)
        }

        fun toPageText(doc: PDDocument, pageIndex: Int): PageText {
            val rotation = runCatching { doc.getPage(pageIndex).rotation }.getOrDefault(0)
            var pageW = positions.firstOrNull()?.pageWidth ?: 0f
            var pageH = positions.firstOrNull()?.pageHeight ?: 0f
            if (pageW <= 1f || pageH <= 1f) {
                runCatching {
                    val p = doc.getPage(pageIndex)
                    val box = p.cropBox
                    pageW = box.width
                    pageH = box.height
                }
            }
            if (positions.isEmpty()) {
                if (pageW <= 1f || pageH <= 1f) {
                    runCatching {
                        val p = doc.getPage(pageIndex)
                        pageW = p.cropBox.width
                        pageH = p.cropBox.height
                    }
                }
                return PageText(pageW, pageH, emptyList(), emptyList(), rotation)
            }

            val chars = positions.mapNotNull { p ->
                val unicode = p.unicode ?: return@mapNotNull null
                if (unicode.isEmpty()) return@mapNotNull null
                val gh = p.heightDir.coerceAtLeast(1f)
                CharBox(
                    text = unicode,
                    x = p.xDirAdj,
                    // yDirAdj is the baseline; shift up so boxes cover the glyph.
                    y = p.yDirAdj - gh,
                    w = p.widthDirAdj.coerceAtLeast(1f),
                    h = gh * 1.25f,
                    space = p.widthOfSpace.takeIf { it.isFinite() && it > 0f } ?: 0f,
                )
            }.filter { it.text.any { ch -> !ch.isWhitespace() } }

            if (chars.isEmpty()) {
                return PageText(pageW, pageH, emptyList(), emptyList(), rotation)
            }
            if (pageW <= 1f) pageW = chars.maxOf { it.x + it.w } + 2f
            if (pageH <= 1f) pageH = chars.maxOf { it.y + it.h } + 2f

            // 1. Group glyphs into visual lines.
            val sorted = chars.sortedWith(compareBy({ it.y }, { it.x }))
            val rawLines = mutableListOf<MutableList<CharBox>>()
            val lineCenters = mutableListOf<Float>()
            for (c in sorted) {
                val cy = c.y + c.h / 2f
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (i in rawLines.indices) {
                    val tol = maxOf(rawLines[i].first().h, c.h) * 0.55f
                    val d = kotlin.math.abs(lineCenters[i] - cy)
                    if (d <= tol && d < bestDist) {
                        best = i; bestDist = d
                    }
                }
                if (best < 0) {
                    rawLines.add(mutableListOf(c))
                    lineCenters.add(cy)
                } else {
                    rawLines[best].add(c)
                    val n = rawLines[best].size
                    lineCenters[best] = (lineCenters[best] * (n - 1) + cy) / n
                }
            }

            // 2. Split each visual line into segments (columns) and words.
            val words = mutableListOf<WordBox>()
            val lineBoxes = mutableListOf<LineBox>()
            for (raw in rawLines) {
                raw.sortBy { it.x }
                var segmentStart = 0
                for (i in 1..raw.size) {
                    val breakHere = i == raw.size || run {
                        val prev = raw[i - 1]
                        val cur = raw[i]
                        cur.x - (prev.x + prev.w) > maxOf(prev.h, cur.h) * 3.2f ||
                            cur.x - (prev.x + prev.w) > pageW * 0.12f
                    }
                    if (!breakHere) continue
                    val segment = raw.subList(segmentStart, i)
                    segmentStart = i
                    val lineStartWord = words.size
                    var current = mutableListOf<CharBox>()
                    var prev: CharBox? = null
                    fun flush() {
                        if (current.isEmpty()) return
                        val text = buildString { current.forEach { append(it.text) } }
                        val cleaned = text.trim { ch ->
                            ch.isWhitespace() || ch in ".,;:!?\"'“”‘’()[]{}<>،؛…—–-«»"
                        }
                        if (cleaned.isNotEmpty()) {
                            val x0 = current.minOf { it.x }
                            val y0 = current.minOf { it.y }
                            val x1 = current.maxOf { it.x + it.w }
                            val y1 = current.maxOf { it.y + it.h }
                            words.add(
                                WordBox(
                                    text = cleaned,
                                    x = x0,
                                    y = y0,
                                    w = (x1 - x0).coerceAtLeast(1f),
                                    h = (y1 - y0).coerceAtLeast(1f),
                                    line = lineBoxes.size,
                                )
                            )
                        }
                        current = mutableListOf()
                    }
                    for (c in segment) {
                        val gap = if (prev == null) 0f else c.x - (prev.x + prev.w)
                        val spaceLimit = maxOf(c.h, prev?.h ?: c.h) * 0.30f
                        val spaceW = maxOf(prev?.space ?: 0f, c.space)
                        val split = spaceW > 0f && gap > spaceW * 0.55f ||
                            gap > spaceLimit && gap > 1.6f
                        if (split) flush()
                        current.add(c)
                        prev = c
                    }
                    flush()
                    if (words.size > lineStartWord) {
                        val slice = words.subList(lineStartWord, words.size)
                        val x0 = slice.minOf { it.x }
                        val y0 = slice.minOf { it.y }
                        val x1 = slice.maxOf { it.x + it.w }
                        val y1 = slice.maxOf { it.y + it.h }
                        lineBoxes.add(
                            LineBox(
                                text = slice.joinToString(" ") { it.text },
                                x = x0,
                                y = y0,
                                w = (x1 - x0).coerceAtLeast(1f),
                                h = (y1 - y0).coerceAtLeast(1f),
                                wordStart = lineStartWord,
                                wordEnd = words.size - 1,
                            )
                        )
                    }
                }
            }

            // 3. Assign stable line ids and sort in reading order.
            val ordered = mutableListOf<WordBox>()
            val orderedLines = mutableListOf<LineBox>()
            lineBoxes.forEachIndexed { idx, l ->
                val slice = words.subList(l.wordStart, l.wordEnd + 1).map { it.copy(line = idx) }
                ordered.addAll(slice)
                orderedLines.add(l)
            }
            return PageText(pageW, pageH, ordered, orderedLines, rotation)
        }
    }

    private data class CharBox(
        val text: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val space: Float,
    )
}
