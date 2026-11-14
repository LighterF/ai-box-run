package ir.lexilens.reader

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardContextBackfill
import ir.lexilens.reader.pdf.ContextImageFactory
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.pdf.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SentenceCropRegressionTest {

    @Test
    fun sentenceStartsBeforeWordLineAndCropCoversWholeSentence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = copyPdf()

        PdfTextExtractor(context, 424242L, pdf).use { extractor ->
            val page = extractor.extract(0)
            assertNotNull(page)
            val word = page!!.words.first { it.text == "odd" }
            val span = page.sentenceSpanFor(word)
            assertNotNull(span)
            assertTrue("sentence must span more than one line", span!!.slices.size >= 2)
            assertEquals("Everybody", page.words[span.slices.first().firstWord].text)
            assertTrue(span.text.contains("odd Harry Potter in his baggy old clothes."))

            PageRenderer(pdf).use { renderer ->
                val crop = ContextImageFactory.create(context, renderer, page, 0, word, span.text)
                assertNotNull("crop must be built for the spoken sentence", crop)
                val bitmap = BitmapFactory.decodeFile(crop!!.path)
                assertNotNull(bitmap)
                assertTrue("crop should include both sentence lines", bitmap!!.height > 120)
                assertTrue("crop should be wider than tall", bitmap.width > bitmap.height)
            }
        }
    }

    @Test
    fun backfillRebuildsOldCardWithTheSpokenSentence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as App
        val pdf = copyPdf()
        val docId = app.db.addDocument("regression-sentence-crop", pdf.absolutePath, 1)
        val cardId = try {
            app.db.insertCard(
                Card(
                    docId = docId,
                    word = "odd",
                    normWord = "odd",
                    sourceLine = "At school, Harry had no one.",
                    sentence = "At school, Harry had no one.",
                    contextRev = 2,
                    page = 0,
                    createdAt = System.currentTimeMillis(),
                    dueAt = System.currentTimeMillis(),
                )
            )
        } catch (t: Throwable) {
            app.db.deleteDocument(docId)
            throw t
        }
        try {
            val old = app.db.cardsForDocument(docId).first { it.id == cardId }
            val refreshed = CardContextBackfill.refresh(context, app, old)
            assertNotNull(refreshed)
            assertEquals(3, refreshed!!.contextRev)
            assertTrue(refreshed.sentence.startsWith("Everybody knew"))
            assertTrue(refreshed.sentence.contains("odd Harry Potter in his baggy old clothes."))
            assertTrue("backfill must create a crop", File(refreshed.cropPath).isFile)

            val stored = app.db.cardsForDocument(docId).first { it.id == cardId }
            assertEquals(refreshed.sentence, stored.sentence)
            assertEquals(3, stored.contextRev)
            assertEquals(refreshed.cropPath, stored.cropPath)
            assertTrue(stored.rectR > stored.rectL)
            assertTrue(stored.rectB > stored.rectT)
            runCatching { File(stored.cropPath).delete() }
        } finally {
            app.db.deleteCard(cardId)
            app.db.deleteDocument(docId)
        }
    }

    private fun copyPdf(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pdf = File(context.cacheDir, "test_sentence.pdf")
        instrumentation.context.assets.open("test_sentence.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        return pdf
    }
}
