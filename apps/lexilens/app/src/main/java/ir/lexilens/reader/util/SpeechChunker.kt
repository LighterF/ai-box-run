package ir.lexilens.reader.util

import java.util.Locale

/**
 * Splits text into utterances at real punctuation and assigns a silence after
 * each one. Some Android TTS engines ignore commas and other punctuation when
 * reading long text; speaking the chunks separately with an explicit silent
 * utterance makes the pauses reliable on every engine.
 */
object SpeechChunker {
    data class Chunk(val text: String, val pauseAfterMs: Long)

    private const val TERMINALS = ".!?…۔؟"
    private const val SOFT = ",;:،؛"
    private const val CLOSING_MARKS = "\"')]}\u201d\u2019\u00bb"
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "eg", "ie", "no", "vol", "fig", "approx", "dept", "est", "al", "ca", "cf",
    )

    fun chunks(raw: String, maxChars: Int = 320): List<Chunk> {
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t\\u000b\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
        if (normalized.isEmpty()) return emptyList()

        val result = mutableListOf<Chunk>()
        val paragraphs = normalized.split(Regex("\\n{2,}"))
        paragraphs.forEachIndexed { index, paragraph ->
            val before = result.size
            segment(paragraph.replace('\n', ' ').replace(Regex("\\s+"), " ").trim(), result)
            if (result.size > before && index < paragraphs.lastIndex) {
                val last = result.last()
                result[result.lastIndex] = last.copy(pauseAfterMs = maxOf(last.pauseAfterMs, PARAGRAPH_PAUSE_MS))
            }
        }
        if (result.isEmpty()) return emptyList()
        val expanded = mutableListOf<Chunk>()
        for (chunk in result) {
            val parts = splitLong(chunk.text, maxChars)
            parts.forEachIndexed { index, part ->
                expanded.add(
                    Chunk(part, if (index == parts.lastIndex) chunk.pauseAfterMs else 0L)
                )
            }
        }
        if (expanded.isEmpty()) return emptyList()
        val last = expanded.last()
        expanded[expanded.lastIndex] = last.copy(pauseAfterMs = 0L)
        return expanded
    }

    private fun segment(text: String, out: MutableList<Chunk>) {
        if (text.isEmpty()) return
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c !in TERMINALS && c !in SOFT) {
                i++
                continue
            }
            var punctEnd = i
            while (punctEnd < text.length && (text[punctEnd] in TERMINALS || text[punctEnd] in SOFT)) punctEnd++
            var j = punctEnd
            while (j < text.length && text[j] in CLOSING_MARKS) j++
            val next = if (j < text.length) text[j] else ' '
            if (j == punctEnd && (next.isLetterOrDigit() || next == '_')) {
                i = j
                continue
            }
            if (c == '.' && i > 0 && text[i - 1].isDigit() &&
                j < text.length && text[j].isDigit()
            ) {
                i = j
                continue
            }
            if (c == '.' && isAbbreviationBefore(text, i)) {
                i = j
                continue
            }
            val mark = text.substring(i, j)
            val pause = when {
                '…' in mark -> ELLIPSIS_PAUSE_MS
                mark.any { it in "!?؟۔" } -> STRONG_PAUSE_MS
                '.' in mark -> SENTENCE_PAUSE_MS
                mark.any { it in ";:؛" } -> COLON_PAUSE_MS
                else -> COMMA_PAUSE_MS
            }
            append(out, text.substring(start, j), pause)
            start = j
            i = j
        }
        if (start < text.length) append(out, text.substring(start), 0L)
    }

    private fun append(out: MutableList<Chunk>, raw: String, pauseAfterMs: Long) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.none { it.isLetterOrDigit() } && out.isNotEmpty()) {
            val previous = out.last()
            out[out.lastIndex] = previous.copy(text = previous.text + text, pauseAfterMs = pauseAfterMs)
            return
        }
        out.add(Chunk(text, pauseAfterMs))
    }

    private fun splitLong(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            if (end <= start) end = (start + maxChars).coerceAtMost(text.length)
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) parts.add(part)
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        return parts
    }

    private fun isAbbreviationBefore(text: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && text[start - 1].isLetter()) start--
        val w = text.substring(start, dot)
        if (w.length <= 1) return w.isNotEmpty()
        return w.lowercase(Locale.ROOT) in ABBREVIATIONS
    }

    private const val COMMA_PAUSE_MS = 170L
    private const val COLON_PAUSE_MS = 240L
    private const val SENTENCE_PAUSE_MS = 380L
    private const val STRONG_PAUSE_MS = 430L
    private const val ELLIPSIS_PAUSE_MS = 480L
    private const val PARAGRAPH_PAUSE_MS = 550L
}
