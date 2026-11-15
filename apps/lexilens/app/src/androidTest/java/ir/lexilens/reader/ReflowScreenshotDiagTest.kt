package ir.lexilens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReflowScreenshotDiagTest {

    @Test
    fun writeScreenshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (asset in listOf("en.pdf", "fa.pdf")) {
            val pdf = File(context.cacheDir, "diag_$asset")
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                pdf.outputStream().use { output -> input.copyTo(output) }
            }
            val page = PdfTextExtractor(context, if (asset == "en.pdf") 991L else 992L, pdf).use {
                it.extract(0)
            } ?: continue
            val view = ReflowPageView(context).apply {
                setChromeVisible(false)
                textSizePercent = 100
                pageBackground = Color.WHITE
                pageTextColor = Color.BLACK
                setContent(ReflowText.build(page))
            }
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1500, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1080, 1500)
            val bitmap = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.filesDir, "diag_$asset.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
