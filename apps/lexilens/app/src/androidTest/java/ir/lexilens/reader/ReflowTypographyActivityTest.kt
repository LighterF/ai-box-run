package ir.lexilens.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.pdf.PdfFonts
import ir.lexilens.reader.reader.ReaderActivity
import ir.lexilens.reader.reader.ReflowFonts
import ir.lexilens.reader.reader.ReflowPageView
import ir.lexilens.reader.reader.ReflowThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReflowTypographyActivityTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun reflowUsesPdfFontAndSwitchesToBundledFont() {
        val app = context.applicationContext as App
        val prefs = Prefs(context)
        prefs.reflowEnabled = false
        prefs.reflowFontId = ReflowFonts.ID_PDF
        prefs.reflowBackground = ReflowThemes.default.background
        prefs.reflowTextColor = ReflowThemes.default.text

        val pdf = File(context.cacheDir, "typography-activity.pdf")
        instrumentation.context.assets.open("italic.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val docId = app.db.addDocument("typography-ui-test", pdf.absolutePath, 1)
        val extracted = PdfFonts.load(context, docId, pdf)
        assertTrue("italic.pdf should expose its embedded serif", extracted.embedded)

        var scenario: ActivityScenario<ReaderActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_DOC_ID, docId)
            )
            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.reflow_button).performClick()
            }
            waitForReflowText(scenario)
            waitForPdfFont(scenario, extracted.regular!!)

            scenario.onActivity { activity ->
                val reflow = activity.findViewById<ReflowPageView>(R.id.reflow_view)
                assertFalse(reflow.content.isEmpty)
                assertNotNull("reflow should hold the PDF's own face", reflow.textTypeface)
                save(activity.window.decorView, "reflow_italic_font.png")
            }

            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.palette_button).performClick()
            }
            SystemClock.sleep(900)
            var sheet: View? = null
            instrumentation.runOnMainSync { sheet = findThemeSheetWindow() }
            assertNotNull("appearance sheet should be visible", sheet)
            instrumentation.runOnMainSync {
                save(sheet!!, "reflow_font_sheet.png")
            }

            var fontChipFound = false
            scenario.onActivity {
                val panel = findThemeSheetWindow()
                val chip = panel?.let { findViewByDescription(it, "فونت وزیرمتن") }
                fontChipFound = chip != null
                chip?.performClick()
            }
            assertTrue("the font row should offer Vazirmatn", fontChipFound)
            SystemClock.sleep(500)
            scenario.onActivity {
                assertEquals(ReflowFonts.ID_VAZIRMATN, Prefs(it).reflowFontId)
                val reflow = it.findViewById<ReflowPageView>(R.id.reflow_view)
                val vazir = ResourcesCompat.getFont(it, R.font.vazirmatn_regular)!!
                assertTrue(
                    "the reader should now measure with Vazirmatn",
                    sameWidths(reflow.textTypeface, vazir),
                )
                findThemeSheetWindow()?.let { panel ->
                    save(panel, "reflow_font_vazir.png")
                }
            }
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            SystemClock.sleep(500)
            scenario.onActivity { activity ->
                save(activity.window.decorView, "reflow_font_vazir_page.png")
            }
            for (name in listOf(
                "reflow_italic_font.png",
                "reflow_font_sheet.png",
                "reflow_font_vazir.png",
                "reflow_font_vazir_page.png",
            )) {
                assertTrue("$name should contain a real screenshot", File(context.filesDir, name).length() > 10_000)
            }
        } finally {
            scenario?.close()
            runCatching { app.db.deleteDocument(docId) }
            runCatching { pdf.delete() }
            prefs.apply {
                reflowEnabled = false
                reflowFontId = ReflowFonts.ID_PDF
                reflowTextColor = ReflowThemes.default.text
                reflowBackground = ReflowThemes.default.background
            }
        }
    }

    private fun waitForReflowText(scenario: ActivityScenario<ReaderActivity>) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val reflow = activity.findViewById<ReflowPageView>(R.id.reflow_view)
                ready = reflow.visibility == View.VISIBLE && !reflow.content.isEmpty
            }
            if (ready) return
            SystemClock.sleep(250)
        }
        throw AssertionError("reflow text did not appear within 20s")
    }

    private fun waitForPdfFont(scenario: ActivityScenario<ReaderActivity>, expected: Typeface) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val reflow = activity.findViewById<ReflowPageView>(R.id.reflow_view)
                ready = sameWidths(reflow.textTypeface, expected)
            }
            if (ready) return
            SystemClock.sleep(250)
        }
        throw AssertionError("the PDF font was not applied within 15s")
    }

    private fun sameWidths(first: Typeface, second: Typeface): Boolean {
        val sample = "Reading راحت است 123"
        val a = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = first; textSize = 48f }
        val b = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = second; textSize = 48f }
        return kotlin.math.abs(a.measureText(sample) - b.measureText(sample)) < 1f
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        File(context.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun findViewByDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewByDescription(view.getChildAt(i), description)?.let { return it }
            }
        }
        return null
    }

    private fun findThemeSheetWindow(): View? {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = field.get(instance) as List<View>
        return views.firstOrNull { it.findViewById<View>(R.id.font_row) != null }
    }
}
