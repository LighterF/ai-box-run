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
