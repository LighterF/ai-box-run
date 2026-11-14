package ir.lexilens.reader.data

import android.content.Context
import ir.lexilens.reader.App
import ir.lexilens.reader.pdf.ContextImageFactory
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.util.TextUtils
import java.io.File
import java.util.UUID

object CardComposer {

    const val CONTEXT_REV = 3

    fun build(
        context: Context,
        app: App,
        doc: Document,
        pageText: PageText,
        pageIndex: Int,
        word: WordBox,
        entry: DictionaryEntry?,
        translationOverride: String,
        renderer: PageRenderer,
        ttsRate: Float,
    ): Card {
        val rawLine = pageText.lines.getOrNull(word.line)?.text?.trim().orEmpty()
            .ifBlank { word.text }
        val rawSentence = pageText.sentenceFor(word).trim().ifBlank { rawLine }
        val sourceLine = TextUtils.excerptAround(rawLine, word.text, 200)
        val sentence = TextUtils.sentenceAround(rawSentence, word.text, TextUtils.CONTEXT_MAX_CHARS)
        val crop = runCatching {
            ContextImageFactory.create(context, renderer, pageText, pageIndex, word, sentence)
        }.getOrNull()
        val translation = translationOverride.trim().ifBlank {
            entry?.translations?.joinToString("\n").orEmpty()
        }
        val norm = TextUtils.cleanToken(word.text).ifBlank { TextUtils.normalizeWord(word.text) }
        val now = System.currentTimeMillis()
        return Card(
            id = 0L,
            docId = doc.id,
            word = word.text,
            normWord = norm,
            translation = translation,
            ipa = entry?.ipa.orEmpty(),
            pos = entry?.pos.orEmpty(),
            sourceLine = sourceLine,
            sentence = sentence,
            contextRev = CONTEXT_REV,
            page = pageIndex,
            cropPath = crop?.path.orEmpty(),
            rectL = crop?.left ?: 0f,
            rectT = crop?.top ?: 0f,
            rectR = crop?.right ?: 0f,
            rectB = crop?.bottom ?: 0f,
            audioPath = "",
            createdAt = now,
            dueAt = now,
            intervalDays = 0.0,
            ease = 2.5,
            reps = 0,
            lapses = 0,
            state = CardState.NEW.ordinal,
            step = 0,
            lastReview = 0L,
            suspended = false,
            note = "",
            docTitle = doc.title,
        )
    }

    fun synthesizeAudio(app: App, card: Card, rate: Float): String {
        return runCatching {
            val dir = File(app.filesDir, "audio").apply { mkdirs() }
            val target = File(dir, "card_${UUID.randomUUID()}.wav")
            if (app.tts.synthesize(card.word, target, rate)) target.absolutePath else ""
        }.getOrDefault("")
    }
}
