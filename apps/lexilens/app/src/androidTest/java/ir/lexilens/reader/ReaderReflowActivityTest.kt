package ir.lexilens.reader

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.reader.ReflowThemes
import ir.lexilens.reader.reader.ReaderActivity
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderReflowActivityTest {

    @Test
    fun reflowButtonShowsTextAndThemeSheetOpens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as App
        Prefs(context).reflowEnabled = false
        val pdf = File(context.cacheDir, "reflow_activity.pdf")
        instrumentation.context.assets.open("test_sentence.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val docId = app.db.addDocument("reflow-ui-test", pdf.absolutePath, 1)
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
            scenario.onActivity { activity ->
                val reflow = activity.findViewById<ReflowPageView>(R.id.reflow_view)
                val page = activity.findViewById<View>(R.id.page_view)
                assertEquals(View.VISIBLE, reflow.visibility)
                assertEquals(View.GONE, page.visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.palette_button).visibility)
                assertFalse("reflow should contain the PDF text", reflow.content.isEmpty)
                val before = reflow.textSizePercent
                saveDecor(activity, "reflow_size_100.png")
                repeat(5) { activity.findViewById<View>(R.id.text_larger).performClick() }
                assertTrue("A+ should enlarge the text", reflow.textSizePercent >= before + 50)
                saveDecor(activity, "reflow_activity.png")
            }
            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.palette_button).performClick()
            }
            SystemClock.sleep(800)
            var sheetDecor: View? = null
            instrumentation.runOnMainSync {
                sheetDecor = findThemeSheetWindow()
            }
            assertTrue("theme bottom sheet should be on screen", sheetDecor != null)
            val screen = Bitmap.createBitmap(
                sheetDecor!!.width.coerceAtLeast(1),
                sheetDecor!!.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            sheetDecor!!.draw(Canvas(screen))
            File(context.filesDir, "reflow_theme_sheet.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screen.recycle()
            assertTrue(File(context.filesDir, "reflow_activity.png").length() > 10_000)
            assertTrue(File(context.filesDir, "reflow_size_100.png").length() > 10_000)
            assertTrue(File(context.filesDir, "reflow_theme_sheet.png").length() > 10_000)

            var presetFound = false
            scenario.onActivity { activity ->
                val sheet = findThemeSheetWindow()
                val night = sheet?.let { findViewByDescription(it, "پریست شب") }
                presetFound = night != null
                night?.performClick()
                assertEquals(0xFF0B1512.toInt(), Prefs(activity).reflowBackground)
                assertEquals(0xFFE7F0EC.toInt(), Prefs(activity).reflowTextColor)
                assertEquals(
                    0xFF0B1512.toInt(),
                    activity.findViewById<ReflowPageView>(R.id.reflow_view).pageBackground,
                )
            }
            assertTrue("the night preset should be clickable", presetFound)
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(600)
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(context.filesDir, "reflow_activity_night.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            assertTrue(File(context.filesDir, "reflow_activity_night.png").length() > 10_000)
        } finally {
            scenario?.close()
            runCatching { app.db.deleteDocument(docId) }
            runCatching { pdf.delete() }
            Prefs(context).apply {
                reflowEnabled = false
                reflowBackground = ReflowThemes.default.background
                reflowTextColor = ReflowThemes.default.text
            }
        }
    }

    private fun saveDecor(activity: ReaderActivity, name: String) {
        val decor = activity.window.decorView
        assertTrue(decor.width > 0 && decor.height > 0)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(activity.filesDir, name).outputStream().use {
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
        return views.firstOrNull { it.findViewById<View>(R.id.preset_grid) != null }
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
}
