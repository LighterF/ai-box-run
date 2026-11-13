package ir.lexilens.reader.util

import java.text.Normalizer
import java.util.Locale

object TextUtils {
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
        val idx = if (token.isEmpty()) -1 else text.indexOf(token, ignoreCase = true)
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
