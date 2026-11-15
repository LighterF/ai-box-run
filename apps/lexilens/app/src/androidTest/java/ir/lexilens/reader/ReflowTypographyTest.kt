package ir.lexilens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.LineBox
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.PdfFonts
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.reader.ReflowFonts
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReflowTypographyTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun italicRunsAreDetectedInThePdfAndSurviveReflow() {
        val page = extract("italic.pdf", 7101L)
        val i = page.words.firstOrNull { it.text == "I" }
        val want = page.words.firstOrNull { it.text == "want" }
        val whole = page.words.firstOrNull { it.text == "whole" }
        assertNotNull("the italic I should be extracted", i)
        assertNotNull(want)
        assertTrue("only the I is italic in the first sentence", i!!.italic)
        assertFalse("the rest of that sentence stays upright", want!!.italic)
        assertTrue("a fully italic line keeps its words italic", whole!!.italic)

        val content = ReflowText.build(page)
        val paragraph = content.paragraphs.first { it.text.contains("I want to read") }
        val italicWord = page.words.indexOf(i)
        val plainWord = page.words.indexOf(want)
        val italicSpan = paragraph.words.first { it.wordIndex == italicWord }
        val plainSpan = paragraph.words.first { it.wordIndex == plainWord }
        assertTrue(italicSpan.italic)
        assertEquals("I", paragraph.text.substring(italicSpan.start, italicSpan.end))
        assertFalse(plainSpan.italic)
        assertEquals("want", paragraph.text.substring(plainSpan.start, plainSpan.end))
    }

    @Test
    fun embeddedPdfFontWithUnicodeCmapIsExtracted() {
        clearCaches(7102L)
        val face = PdfFonts.load(context, 7102L, copyAsset("italic.pdf"))
        assertTrue("a Unicode-map TrueType subset should be extractable", face.embedded)
        assertNotNull("the extracted face must load in Android", face.regular)
        assertNotNull("the PDF's italic cut should be extracted too", face.italic)
        assertTrue(face.familyName.orEmpty().contains("Serif"))

        val width = { typeface: android.graphics.Typeface, text: String ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 40f
            }.measureText(text)
        }
        assertTrue(
            "the extracted serif should measure differently from the system serif",
            kotlin.math.abs(width(face.regular!!, "Reading") - width(android.graphics.Typeface.SERIF, "Reading")) > 1f,
        )
    }

    @Test
    fun macRomanOnlySubsetFallsBackInsteadOfRenderingSystemGlyphs() {
        // Reportlab-style subsets use a Macintosh cmap (platform 1); Android
        // cannot map text through it, so such a file must be rejected.
        clearCaches(7104L)
        val face = PdfFonts.load(context, 7104L, copyAsset("macsubset.pdf"))
        assertFalse(face.embedded)
        assertTrue(face.regular == null)
        assertTrue(face.familyName.orEmpty().contains("Serif"))
    }

    @Test
    fun persianPdfDefaultsToItsOwnVazirmatnFace() {
        clearCaches(7103L)
        val face = PdfFonts.load(context, 7103L, copyAsset("fa.pdf"))
        assertTrue(face.embedded)
        assertNotNull(face.regular)
        assertTrue(
            "the Persian PDF's body font should be Vazirmatn, not a system default",
            face.familyName.orEmpty().contains("Vazir"),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face.regular }
        assertTrue(paint.hasGlyph("م"))
    }

    @Test
    fun italicSpanActuallyChangesThePaintedGlyphs() {
        val plain = renderSample(italic = false)
        val italic = renderSample(italic = true)
        assertEquals(plain.width, italic.width)
        var changed = 0
        for (y in 0 until plain.height) {
            for (x in 0 until plain.width) {
                if (plain.getPixel(x, y) != italic.getPixel(x, y)) changed++
            }
        }
        plain.recycle()
        italic.recycle()
        assertTrue("italic styling should visibly slant the word, changed=$changed", changed > 200)
    }

    @Test
    fun importFontCopiesValidatesAndDeletes() {
        val source = File(context.cacheDir, "import-source.ttf")
        context.resources.openRawResource(R.font.vazirmatn_regular).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val option = ReflowFonts.importFrom(context, Uri.fromFile(source))
        assertNotNull("a real font file should be accepted", option)
        assertTrue(option!!.removable)
        assertTrue(option.typeface.isBold.not())

        val stored = File(ReflowFonts.importDir(context), option.id.removePrefix("imported:"))
        assertTrue("the imported copy should live in app storage", stored.exists())
        assertTrue(ReflowFonts.importedOptions(context).any { it.id == option.id })

        assertTrue(ReflowFonts.deleteImported(context, option.id))
        assertFalse(stored.exists())
        assertFalse(ReflowFonts.importedOptions(context).any { it.id == option.id })
    }

    // ------------------------------------------------------------------ utils

    private fun extract(asset: String, docId: Long): PageText {
        clearCaches(docId)
        val extractor = PdfTextExtractor(context, docId, copyAsset(asset))
        return try {
            extractor.extract(0) ?: throw AssertionError("no text in $asset")
        } finally {
            extractor.close()
        }
    }

    private fun clearCaches(docId: Long) {
        File(context.filesDir, "text/$docId").deleteRecursively()
        File(context.filesDir, "pdf-fonts/$docId.json").delete()
        File(context.filesDir, "pdf-fonts/pdf-$docId-regular.ttf").delete()
        File(context.filesDir, "pdf-fonts/pdf-$docId-regular.otf").delete()
        File(context.filesDir, "pdf-fonts/pdf-$docId-italic.ttf").delete()
        File(context.filesDir, "pdf-fonts/pdf-$docId-italic.otf").delete()
    }

    private fun copyAsset(asset: String): File {
        val target = File(context.cacheDir, "typography-$asset")
        instrumentation.context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun renderSample(italic: Boolean): Bitmap {
        val words = mutableListOf<WordBox>()
        val text = "I want to read this book."
        var x = 10f
        text.trim('.').split(' ').forEach { token ->
            val width = (token.length * 9f + 6f).coerceAtLeast(8f)
            words.add(
                WordBox(
                    text = token,
                    x = x,
                    y = 20f,
                    w = width,
                    h = 30f,
                    line = 0,
                    italic = italic && token == "I",
                )
            )
            x += width + 6f
        }
        val page = PageText(
            612f,
            792f,
            words,
            listOf(LineBox(text, 10f, 20f, x - 10f, 30f, 0, words.size - 1)),
        )
        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = 200
            setContent(ReflowText.build(page))
        }
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 700)
        SystemClock.sleep(50)
        val bitmap = Bitmap.createBitmap(1080, 700, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }
}
