package ir.lexilens.reader.data

import android.content.Context
import ir.lexilens.reader.App
import ir.lexilens.reader.pdf.ContextImageFactory
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.util.TextUtils
import java.io.File

/**
 * Rebuilds both the sentence and its image from the source PDF. Revision 3 is
 * the first one where the image is a multi-line crop of exactly the spoken
 * sentence, so older cards must be refreshed (even if their text was already
 * full) before we can trust their crop again.
 */
object CardContextBackfill {
    fun needsRefresh(card: Card): Boolean =
        card.contextRev < CardComposer.CONTEXT_REV ||
            card.sentence.isBlank()

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
                val sentence = TextUtils.sentenceAround(
                    rawSentence,
                    card.word,
                    TextUtils.CONTEXT_MAX_CHARS,
                )
                if (sentence.isBlank()) return@use null
                val sourceLine = card.sourceLine.ifBlank {
                    TextUtils.excerptAround(lineText, card.word, 200)
                }
                val crop = PageRenderer(pdf).use { renderer ->
                    runCatching {
                        ContextImageFactory.create(
                            context = context.applicationContext,
                            renderer = renderer,
                            pageText = page,
                            pageIndex = card.page,
                            word = word,
                            sentence = sentence,
                        )
                    }.getOrNull()
                }
                if (card.cropPath.isNotBlank() && card.cropPath != crop?.path) {
                    runCatching { File(card.cropPath).delete() }
                }
                app.db.updateCardContext(
                    id = card.id,
                    sentence = sentence,
                    sourceLine = sourceLine,
                    contextRev = CardComposer.CONTEXT_REV,
                    cropPath = crop?.path.orEmpty(),
                    rectL = crop?.left ?: 0f,
                    rectT = crop?.top ?: 0f,
                    rectR = crop?.right ?: 0f,
                    rectB = crop?.bottom ?: 0f,
                )
                card.copy(
                    sentence = sentence,
                    sourceLine = sourceLine,
                    contextRev = CardComposer.CONTEXT_REV,
                    cropPath = crop?.path.orEmpty(),
                    rectL = crop?.left ?: 0f,
                    rectT = crop?.top ?: 0f,
                    rectR = crop?.right ?: 0f,
                    rectB = crop?.bottom ?: 0f,
                )
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
