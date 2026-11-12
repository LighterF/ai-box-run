package ir.lexilens.reader.pdf

import org.json.JSONArray
import org.json.JSONObject

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
}

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
        root.put("v", 2)
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

    fun sentenceFor(word: WordBox): String {
        val idx = word.line
        if (idx !in lines.indices) return word.text
        var start = idx
        var end = idx
        val maxLines = 6
        while (start > 0 && idx - start < maxLines) {
            val prev = lines[start - 1]
            if (endsSentence(prev.text)) break
            if (lines[start].y - prev.bottom > prev.h * 1.8f) break
            start--
        }
        while (end < lines.lastIndex && end - idx < maxLines) {
            val cur = lines[end]
            if (endsSentence(cur.text)) break
            if (lines[end + 1].y - cur.bottom > cur.h * 1.8f) break
            end++
        }
        val sb = StringBuilder()
        for (i in start..end) {
            val t = lines[i].text.trim().trimEnd('-')
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun endsSentence(raw: String): Boolean {
        val t = raw.trimEnd()
        if (t.isEmpty()) return false
        return t.last() in ".!?۔"
    }

    companion object {
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
