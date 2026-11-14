package ir.lexilens.reader.util

import java.text.Normalizer
import java.util.Locale

object TextUtils {
    private const val SENTENCE_ENDS = ".!?…۔؟"
    private const val CLOSING_MARKS = "\"')]}\u201d\u2019\u00bb"
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "eg", "ie", "no", "vol", "fig", "approx", "dept", "est", "al", "ca", "cf",
    )
    private val punct = " \t\r\n.,;:!?\"'“”‘’()[]{}<>،؛…—–-«»/\\|".toSet()
    private val arabicFix = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ۀ' to 'ه',
        'أ' to 'ا', 'إ' to 'ا', 'ؤ' to 'و',
    )

    fun normalizeWord(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        s = buildString { for (ch in s) append(arabicFix[ch] ?: ch) }
        s = s.trim { it in punct }
        s = s.replace(Regex("\\s+"), " ")
        return s.lowercase(Locale.ROOT)
    }

    fun cleanToken(raw: String): String {
        var s = normalizeWord(raw)
        // Possessive: "reader's" -> "reader"
        if (s.length > 2 && s.endsWith("'s")) s = s.dropLast(2)
        return s
    }

    fun morphologyCandidates(word: String): List<String> {
        val w = word.lowercase(Locale.ROOT)
        val out = LinkedHashSet<String>()
        fun add(x: String) { if (x.length >= 2 && x != w) out.add(x) }

        if (w.endsWith("'s")) add(w.dropLast(2))
        if (w.endsWith("s'")) add(w.dropLast(2))
        if (w.endsWith("ies") && w.length > 4) add(w.dropLast(3) + "y")
        if (w.endsWith("ves") && w.length > 4) {
            add(w.dropLast(3) + "f")
            add(w.dropLast(3) + "fe")
        }
        if (w.endsWith("es") && w.length > 3) {
            add(w.dropLast(2))
            add(w.dropLast(1))
        }
        if (w.endsWith("s") && w.length > 2) add(w.dropLast(1))
        if (w.endsWith("ied") && w.length > 4) add(w.dropLast(3) + "y")
        if (w.endsWith("ed") && w.length > 3) {
            add(w.dropLast(2))
            add(w.dropLast(1))
            if (w.length > 4 && w[w.length - 3] == w[w.length - 4]) add(w.dropLast(2).dropLast(1))
        }
        if (w.endsWith("ing") && w.length > 4) {
            add(w.dropLast(3))
            add(w.dropLast(3) + "e")
            if (w.length > 5 && w[w.length - 4] == w[w.length - 5]) add(w.dropLast(3).dropLast(1))
        }
        if (w.endsWith("ly") && w.length > 4) {
            add(w.dropLast(2))
            if (w.endsWith("ily")) add(w.dropLast(3) + "y")
        }
        if (w.endsWith("er") && w.length > 4) {
            add(w.dropLast(2))
            add(w.dropLast(1))
        }
        if (w.endsWith("est") && w.length > 5) {
            add(w.dropLast(3))
            add(w.dropLast(2))
        }
        return out.toList()
    }

    fun translationLines(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Returns a readable excerpt around [word]. Long PDF lines (some generators
     * emit a whole paragraph as one line) are cut at sentence or word
     * boundaries so playback never reads an entire page.
     */
    fun excerptAround(raw: String, word: String, maxChars: Int = 220): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxChars) return text
        val token = word.trim()
        val idx = if (token.isEmpty()) -1 else indexOfToken(text, token)
        if (idx < 0) return cutAtWord(text, 0, maxChars)

        val endWord = (idx + token.length).coerceAtMost(text.length)
        val budget = (maxChars - token.length).coerceAtLeast(32)
        var start = (idx - budget / 2).coerceAtLeast(0)
        var end = (endWord + budget / 2).coerceAtMost(text.length)

        val stops = charArrayOf('.', '!', '?', ';', '۔', '\n')
        val previousStop = if (idx > 0) text.lastIndexOfAny(stops, idx - 1) else -1
        if (previousStop >= 0 && idx - previousStop <= 90) start = previousStop + 1
        val nextStop = text.indexOfAny(stops, endWord)
        if (nextStop in 0..(endWord + 90)) end = nextStop + 1

        if (end - start > maxChars) {
            start = (idx - budget / 2).coerceAtLeast(0)
            end = (endWord + budget / 2).coerceAtMost(text.length)
        }
        if (start > 0) {
            val space = text.lastIndexOf(' ', start)
            if (space >= 0) start = space + 1
        }
        if (end < text.length) {
            val space = text.indexOf(' ', end)
            if (space >= 0) end = space
        }
        val core = text.substring(start, end).trim()
        val prefix = if (start > 0) "… " else ""
        val suffix = if (end < text.length) " …" else ""
        return prefix + core + suffix
    }

    /**
     * Returns the sentence that contains [word], using real punctuation as the
     * boundary. If the sentence is longer than [maxChars], a window around the
     * word is returned without ever cutting the word itself. This matters when
     * the selected word sits at the very end of a PDF line: the sentence keeps
     * going on the next line, and the review card must show that continuation.
     */
    fun sentenceAround(raw: String, word: String, maxChars: Int = 320): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""
        val token = word.trim()
        if (token.isEmpty()) return excerptAround(text, word, maxChars)
        val idx = indexOfToken(text, token)
        if (idx < 0) return excerptAround(text, word, maxChars)
        val wordEnd = (idx + token.length).coerceAtMost(text.length)
        val sentenceStart = findSentenceStart(text, idx)
        val sentenceEnd = findSentenceEnd(text, wordEnd)
        if (sentenceEnd <= sentenceStart) return excerptAround(text, word, maxChars)
        val sentence = text.substring(sentenceStart, sentenceEnd).trim()
        if (sentence.length <= maxChars) return sentence
        return windowAround(text, idx, wordEnd, sentenceStart, sentenceEnd, maxChars)
    }

    private fun indexOfToken(text: String, token: String): Int {
        var from = 0
        while (from <= text.length - token.length) {
            val i = text.indexOf(token, from, ignoreCase = true)
            if (i < 0) break
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + token.length < text.length) text[i + token.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return i
            from = i + 1
        }
        return text.indexOf(token, ignoreCase = true)
    }

    private fun findSentenceStart(text: String, before: Int): Int {
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

    private fun findSentenceEnd(text: String, from: Int): Int {
        var i = from.coerceAtMost(text.length)
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

    private fun windowAround(
        text: String,
        wordStart: Int,
        wordEnd: Int,
        sentenceStart: Int,
        sentenceEnd: Int,
        maxChars: Int,
    ): String {
        val budget = (maxChars - (wordEnd - wordStart)).coerceAtLeast(32)
        var start = (wordStart - budget / 2).coerceAtLeast(sentenceStart)
        var end = (wordEnd + budget / 2).coerceAtMost(sentenceEnd)
        if (end - start > maxChars) {
            end = (start + maxChars).coerceAtLeast(wordEnd).coerceAtMost(sentenceEnd)
        }
        if (start > sentenceStart) {
            val space = text.lastIndexOf(' ', start)
            if (space >= sentenceStart) start = space + 1
        }
        if (end < sentenceEnd) {
            val space = text.lastIndexOf(' ', end)
            if (space >= wordEnd) end = space
        }
        start = start.coerceAtMost(wordStart)
        end = end.coerceAtLeast(wordEnd)
        val core = text.substring(start, end).trim()
        val prefix = if (start > sentenceStart) "… " else ""
        val suffix = if (end < sentenceEnd) " …" else ""
        return prefix + core + suffix
    }

    private fun cutAtWord(text: String, start: Int, maxChars: Int): String {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length) {
            val space = text.indexOf(' ', end)
            if (space >= 0) end = space
        }
        val core = text.substring(start, end).trim()
        return if (start > 0) "… $core …" else "$core …"
    }

    fun firstTranslation(raw: String): String = translationLines(raw).firstOrNull().orEmpty()

    fun faDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    '0' -> '۰'; '1' -> '۱'; '2' -> '۲'; '3' -> '۳'; '4' -> '۴'
                    '5' -> '۵'; '6' -> '۶'; '7' -> '۷'; '8' -> '۸'; '9' -> '۹'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun faNumber(value: Int): String = faDigits(value.toString())

    fun relativeTime(epoch: Long): String {
        if (epoch <= 0) return "—"
        val diff = System.currentTimeMillis() - epoch
        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L
        return when {
            minutes < 1 -> "همین حالا"
            minutes < 60 -> "${faNumber(minutes.toInt())} دقیقه پیش"
            hours < 24 -> "${faNumber(hours.toInt())} ساعت پیش"
            days < 30 -> "${faNumber(days.toInt())} روز پیش"
            else -> "${faNumber((days / 30).toInt())} ماه پیش"
        }
    }

    fun dueLabel(card: ir.lexilens.reader.data.Card): String {
        if (card.suspended) return "معلق"
        val now = System.currentTimeMillis()
        return when {
            card.state == ir.lexilens.reader.data.CardState.NEW.ordinal -> "جدید"
            card.dueAt <= now -> "آماده مرور"
            else -> {
                val mins = ((card.dueAt - now) / 60_000L).coerceAtLeast(1)
                when {
                    mins < 60 -> "${faNumber(mins.toInt())} دقیقه دیگر"
                    mins < 1440 -> "${faNumber((mins / 60).toInt())} ساعت دیگر"
                    else -> "${faNumber((mins / 1440).toInt())} روز دیگر"
                }
            }
        }
    }

    fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val v = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$v\"" else v
    }
}
