package ir.lexilens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end regression for a PDF with generous leading (30pt lines, 12pt
 * font). Earlier versions used the font height as the line step, so every one
 * of these lines looked like an empty line to the reflow and was split into its
 * own paragraph - even after a comma.
 */
@RunWith(AndroidJUnit4::class)
class ReflowLeadingPdfTest {

    @Test
    fun generousLeadingKeepsParagraphsAndBlankLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val docId = 993L
        File(context.filesDir, "text/$docId").deleteRecursively()
        val pdf = File(context.cacheDir, "reflow_leading.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("leading.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val page = PdfTextExtractor(context, docId, pdf).use { it.extract(0) }
        assertTrue("fixture must have text", page != null && page.lines.size >= 9)

        val content = ReflowText.build(page!!)

        assertEquals("three paragraphs, not one per line", 3, content.paragraphs.size)
        assertEquals(0, content.paragraphs[0].blankLinesBefore)
        assertEquals(1, content.paragraphs[1].blankLinesBefore)
        assertEquals(2, content.paragraphs[2].blankLinesBefore)
        assertTrue(content.paragraphs[0].text.contains("place, and every morning"))
        assertTrue(content.paragraphs[0].text.length > 150)

        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            textSizePercent = 100
            pageBackground = Color.rgb(246, 244, 237)
            pageTextColor = Color.rgb(20, 35, 30)
            setContent(content)
        }
        val width = 1080
        val height = 1920
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val shot = File(context.filesDir, "reflow_leading.png")
        shot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue("screenshot should not be blank", shot.length() > 10_000)

        File(context.filesDir, "text/$docId").deleteRecursively()
    }
}
