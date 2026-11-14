package ir.lexilens.reader.data

import android.content.Context
import ir.lexilens.reader.App
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.util.TextUtils
import java.io.File

/**
 * Older releases saved only a short excerpt of the sentence around a word.
 * When such a card is opened, rebuild its context from the source PDF once and
 * store the full sentence, so reviews show the same text as new cards.
 */
object CardContextBackfill {
    private const val SENTENCE_MAX_CHARS = 1600

    fun needsRefresh(card: Card): Boolean =
        TextUtils.needsContextRefresh(card.sentence)

    fun refresh(context: Context, app: App, card: Card): Card? {
        if (!needsRefresh(card)) return null
        val doc = app.db.document(card.docId) ?: return null
        val pdf = File(doc.path)
        if (!pdf.isFile) return null
        return runCatching {
            PdfTextExtractor(context.applicationContext, doc.id, pdf).use { extractor ->
                val page = extractor.extract(card.page) ?: return@use null
                val word = locateWord(page, card) ?: return@use null
                val lineText = page.lines.getOrNull(word.line)?.text.orEmpty()
                val rawSentence = page.sentenceFor(word).trim().ifBlank { lineText.trim() }
                val sentence = TextUtils.sentenceAround(rawSentence, card.word, SENTENCE_MAX_CHARS)
                if (sentence.isBlank()) return@use null
                val sourceLine = card.sourceLine.ifBlank {
                    TextUtils.excerptAround(lineText, card.word, 200)
                }
                app.db.updateCardContext(card.id, sentence, sourceLine)
                card.copy(sentence = sentence, sourceLine = sourceLine)
            }
        }.getOrNull()
    }

    private fun locateWord(page: PageText, card: Card): WordBox? {
        val token = TextUtils.cleanToken(card.word)
        if (token.isEmpty()) return null
        val candidates = page.words.filter { word ->
            TextUtils.cleanToken(word.text) == token ||
                TextUtils.normalizeWord(word.text) == token
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val stored = normalizeSpace(card.sourceLine)
        if (stored.isNotEmpty()) {
            val byLine = candidates.firstOrNull { word ->
                val line = page.lines.getOrNull(word.line)?.text.orEmpty()
                normalizeSpace(TextUtils.excerptAround(line, card.word, 200)) == stored
            }
            if (byLine != null) return byLine
        }
        return candidates.first()
    }

    private fun normalizeSpace(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()
}
