package ir.lexilens.reader.pdf

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class WordBox(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val line: Int,
) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f

    fun contains(px: Float, py: Float, tolerance: Float): Boolean {
        return px >= x - tolerance && px <= right + tolerance &&
            py >= y - tolerance && py <= bottom + tolerance
    }
}

data class LineBox(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val wordStart: Int,
    val wordEnd: Int,
) {
    val bottom: Float get() = y + h
    val right: Float get() = x + w
}

/** One line fragment that belongs to a sentence, with the words to crop for it. */
data class LineSlice(
    val line: Int,
    val startChar: Int,
    val endChar: Int,
    val firstWord: Int,
    val lastWord: Int,
)

/** Exact sentence around a word plus the per-line pieces that make it up. */
data class SentenceSpan(
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val slices: List<LineSlice>,
)

data class PageText(
    val width: Float,
    val height: Float,
    val words: List<WordBox>,
    val lines: List<LineBox>,
    val rotation: Int = 0,
) {
    val isEmpty: Boolean get() = words.isEmpty()

    fun hit(px: Float, py: Float): Pair<WordBox, LineBox>? {
        if (words.isEmpty()) return null
        val avg = words.map { it.h }.average().toFloat()
        val tolerance = (if (avg.isFinite() && avg > 0f) avg else 10f) * 0.45f
        var best: WordBox? = null
        var bestScore = Float.MAX_VALUE
        for (w in words) {
            if (w.contains(px, py, tolerance)) {
                val dx = w.cx - px
                val dy = w.cy - py
                val score = dx * dx + dy * dy
                if (score < bestScore) {
                    bestScore = score
                    best = w
                }
            }
        }
        if (best == null) {
            val maxDist = (if (avg.isFinite() && avg > 0f) avg else 10f) * 1.35f
            for (w in words) {
                val dx = w.cx - px
                val dy = w.cy - py
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d <= maxDist && d < bestScore) {
                    bestScore = d
                    best = w
                }
            }
        }
        val word = best ?: return null
        val line = lines.getOrNull(word.line) ?: return null
        return word to line
    }

    /** Maps a point normalized on the rendered page back to PDF text coordinates. */
    fun mapFromPage(nx: Float, ny: Float): Pair<Float, Float> = when (((rotation % 360) + 360) % 360) {
        90 -> (ny.coerceIn(0f, 1f) * width) to ((1f - nx.coerceIn(0f, 1f)) * height)
        180 -> ((1f - nx.coerceIn(0f, 1f)) * width) to ((1f - ny.coerceIn(0f, 1f)) * height)
        270 -> ((1f - ny.coerceIn(0f, 1f)) * width) to (nx.coerceIn(0f, 1f) * height)
        else -> (nx.coerceIn(0f, 1f) * width) to (ny.coerceIn(0f, 1f) * height)
    }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("v", 4)
        root.put("width", width.toDouble())
        root.put("height", height.toDouble())
        root.put("rotation", rotation)
        val ws = JSONArray()
        for (w in words) {
            ws.put(JSONArray().apply {
                put(w.text)
                put(w.x.toDouble())
                put(w.y.toDouble())
                put(w.w.toDouble())
                put(w.h.toDouble())
                put(w.line)
            })
        }
        root.put("words", ws)
        val ls = JSONArray()
        for (l in lines) {
            ls.put(JSONArray().apply {
                put(l.text)
                put(l.x.toDouble())
                put(l.y.toDouble())
                put(l.w.toDouble())
                put(l.h.toDouble())
                put(l.wordStart)
                put(l.wordEnd)
            })
        }
        root.put("lines", ls)
        return root
    }

    fun sentenceFor(word: WordBox): String =
        sentenceSpanFor(word)?.text ?: word.text

    /**
     * Finds the sentence around [word] using real punctuation, not PDF line
     * breaks. The returned text can start in the middle of the first line and
     * continue on following lines, so the review screen and the voice always
     * cover the same words. Line gaps and a 16-line cap keep unrelated columns
     * or paragraphs from being joined together.
     */
    fun sentenceSpanFor(word: WordBox): SentenceSpan? {
        val idx = word.line
        if (idx !in lines.indices) return null
        val maxLines = 16
        var firstLine = idx
        var lastLine = idx
        while (firstLine > 0 && idx - firstLine < maxLines) {
            val prev = lines[firstLine - 1]
            val cur = lines[firstLine]
            if (gapBetween(prev, cur) > maxOf(prev.h, cur.h) * 5f) break
            firstLine--
        }
        while (lastLine < lines.lastIndex && lastLine - idx < maxLines) {
            val cur = lines[lastLine]
            val next = lines[lastLine + 1]
            if (gapBetween(cur, next) > maxOf(cur.h, next.h) * 5f) break
            lastLine++
        }

        val texts = ArrayList<String>(lastLine - firstLine + 1)
        val starts = IntArray(lastLine - firstLine + 1)
        val flat = StringBuilder()
        for (i in firstLine..lastLine) {
            if (i > firstLine) flat.append(' ')
            starts[i - firstLine] = flat.length
            val t = normalizeLine(lines[i].text)
            texts.add(t)
            flat.append(t)
        }
        val source = flat.toString()
        if (source.isBlank()) return null

        val localWord = indexOfWord(
            text = texts[idx - firstLine],
            token = word.text,
            approx = approximateOffset(word, lines[idx]),
        )
        if (localWord < 0) return null
        val wordStart = starts[idx - firstLine] + localWord
        val wordEnd = (wordStart + word.text.length).coerceAtMost(source.length)
        var start = sentenceStart(source, wordStart)
        var end = sentenceEnd(source, wordEnd)
        if (end <= start) {
            start = wordStart
            end = wordEnd
        }
        val sentenceText = source.substring(start, end)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (sentenceText.isEmpty()) return null

        val slices = mutableListOf<LineSlice>()
        for (i in firstLine..lastLine) {
            val line = lines[i]
            val text = texts[i - firstLine]
            val lineStart = starts[i - firstLine]
            val lineEnd = lineStart + text.length
            val from = maxOf(start, lineStart)
            val to = minOf(end, lineEnd)
            if (to <= from) continue
            var localStart = (from - lineStart).coerceIn(0, text.length)
            var localEnd = (to - lineStart).coerceIn(localStart, text.length)
            while (localStart < localEnd && text[localStart].isWhitespace()) localStart++
            while (localEnd > localStart && text[localEnd - 1].isWhitespace()) localEnd--
            if (localEnd <= localStart) continue
            val startWord = line.wordStart
            val endWord = line.wordEnd
            if (startWord !in words.indices || endWord !in words.indices || endWord < startWord) continue
            val offsets = ArrayList<Pair<Int, Int>>(endWord - startWord + 1)
            var cursor = 0
            for (w in startWord..endWord) {
                val token = words[w].text
                val p = if (token.isEmpty()) -1 else text.indexOf(token, cursor, ignoreCase = true)
                if (p >= 0) {
                    offsets.add(p to p + token.length)
                    cursor = p + token.length
                } else {
                    offsets.add(-1 to -1)
                }
            }
            var firstWord = -1
            var lastWord = -1
            for (k in offsets.indices) {
                val (ws, we) = offsets[k]
                if (ws < 0) continue
                if (we > localStart && ws < localEnd) {
                    if (firstWord < 0) firstWord = k
                    lastWord = k
                }
            }
            if (firstWord < 0) continue
            slices.add(
                LineSlice(
                    line = i,
                    startChar = localStart,
                    endChar = localEnd,
                    firstWord = startWord + firstWord,
                    lastWord = startWord + lastWord,
                )
            )
        }
        if (slices.isEmpty()) return null
        return SentenceSpan(
            text = sentenceText,
            startLine = slices.first().line,
            endLine = slices.last().line,
            slices = slices,
        )
    }

    private fun gapBetween(above: LineBox, below: LineBox): Float = below.y - above.bottom

    private fun normalizeLine(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private fun approximateOffset(word: WordBox, line: LineBox): Int {
        if (line.w <= 0f || word.w <= 0f) return 0
        val charCount = normalizeLine(line.text).length
        val ratio = ((word.x - line.x) / line.w).coerceIn(0f, 1f)
        return (ratio * charCount).toInt()
    }

    private fun indexOfWord(text: String, token: String, approx: Int): Int {
        if (token.isEmpty()) return -1
        var best = -1
        var bestDistance = Int.MAX_VALUE
        var from = 0
        while (from <= text.length - token.length) {
            val i = text.indexOf(token, from, ignoreCase = true)
            if (i < 0) break
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + token.length < text.length) text[i + token.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) {
                val distance = kotlin.math.abs(i - approx)
                if (distance < bestDistance) {
                    best = i
                    bestDistance = distance
                }
            }
            from = i + 1
        }
        return if (best >= 0) best else approx.coerceIn(0, (text.length - token.length).coerceAtLeast(0))
    }

    private fun sentenceStart(text: String, before: Int): Int {
        var i = before - 1
        while (i >= 0) {
            if (text[i] in SENTENCE_ENDS && isSentenceBoundary(text, i)) {
                var j = i + 1
                while (j < text.length && (text[j].isWhitespace() || text[j] in CLOSING_MARKS)) j++
                return j.coerceAtMost(before)
            }
            i--
        }
        return 0
    }

    private fun sentenceEnd(text: String, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length) {
            if (text[i] in SENTENCE_ENDS && isSentenceBoundary(text, i)) {
                var j = i + 1
                while (j < text.length && text[j] in SENTENCE_ENDS) j++
                while (j < text.length && text[j] in CLOSING_MARKS) j++
                return j
            }
            i++
        }
        return text.length
    }

    private fun isSentenceBoundary(text: String, index: Int): Boolean {
        val c = text[index]
        if (c == '.') {
            if (index > 0 && text[index - 1].isDigit() &&
                index + 1 < text.length && text[index + 1].isDigit()
            ) return false
            if (isAbbreviationBefore(text, index)) return false
        }
        var j = index + 1
        while (j < text.length && text[j] in SENTENCE_ENDS) j++
        if (j < text.length && !text[j].isWhitespace() && text[j] !in CLOSING_MARKS) return false
        return true
    }

    private fun isAbbreviationBefore(text: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && text[start - 1].isLetter()) start--
        val w = text.substring(start, dot)
        if (w.length <= 1) return w.isNotEmpty()
        return w.lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    companion object {
        private const val SENTENCE_ENDS = ".!?…۔؟"
        private const val CLOSING_MARKS = "\"')]}\u201d\u2019\u00bb"
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
            "eg", "ie", "no", "vol", "fig", "approx", "dept", "est", "al", "ca", "cf",
        )

        fun fromJson(root: JSONObject): PageText {
            val words = mutableListOf<WordBox>()
            val wa = root.optJSONArray("words") ?: JSONArray()
            for (i in 0 until wa.length()) {
                val a = wa.getJSONArray(i)
                words.add(
                    WordBox(
                        text = a.getString(0),
                        x = a.getDouble(1).toFloat(),
                        y = a.getDouble(2).toFloat(),
                        w = a.getDouble(3).toFloat(),
                        h = a.getDouble(4).toFloat(),
                        line = a.getInt(5),
                    )
                )
            }
            val lines = mutableListOf<LineBox>()
            val la = root.optJSONArray("lines") ?: JSONArray()
            for (i in 0 until la.length()) {
                val a = la.getJSONArray(i)
                lines.add(
                    LineBox(
                        text = a.getString(0),
                        x = a.getDouble(1).toFloat(),
                        y = a.getDouble(2).toFloat(),
                        w = a.getDouble(3).toFloat(),
                        h = a.getDouble(4).toFloat(),
                        wordStart = a.getInt(5),
                        wordEnd = a.getInt(6),
                    )
                )
            }
            return PageText(
                width = root.getDouble("width").toFloat(),
                height = root.getDouble("height").toFloat(),
                words = words,
                lines = lines,
                rotation = root.optInt("rotation", 0),
            )
        }
    }
}
