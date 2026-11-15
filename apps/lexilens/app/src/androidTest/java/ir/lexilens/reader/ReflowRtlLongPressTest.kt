package ir.lexilens.reader

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.pdf.RtlText
import ir.lexilens.reader.reader.ReaderActivity
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Regression for the "long-press shows a different word" report on RTL PDFs. */
@RunWith(AndroidJUnit4::class)
class ReflowRtlLongPressTest {

    @Test
    fun longPressReportsTheWordThatIsDrawnUnderTheFinger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as App
        val pdf = File(context.cacheDir, "rtl_longpress.pdf")
        instrumentation.context.assets.open("fa.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val page = PdfTextExtractor(context, 993_001L, pdf).use { it.extract(0) }!!
        val content = ReflowText.build(page)
        val target = content.paragraphs.asSequence()
            .flatMap { paragraph -> paragraph.words.asSequence() }
            .first { span ->
                val token = page.words.getOrNull(span.wordIndex)?.text.orEmpty()
                token.count { it.isLetterOrDigit() } >= 4
            }
        val targetWord = page.words[target.wordIndex].text
        val targetLogical = RtlText.visualToLogical(targetWord)

        val docId = app.db.addDocument("rtl-longpress-test", pdf.absolutePath, 1)
        var scenario: ActivityScenario<ReaderActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_DOC_ID, docId)
            )
            scenario.onActivity { activity ->
                activity.findViewById<View>(ir.lexilens.reader.R.id.reflow_button).performClick()
            }
            waitForText(scenario)

            var tapX = -1f
            var tapY = -1f
            scenario.onActivity { activity ->
                val view = activity.findViewById<ReflowPageView>(ir.lexilens.reader.R.id.reflow_view)
                var y = 0
                while (y < view.height && tapY < 0f) {
                    var x = 0
                    while (x < view.width) {
                        if (view.wordIndexAt(x.toFloat(), y.toFloat()) == target.wordIndex) {
                            tapX = x.toFloat()
                            tapY = y.toFloat()
                            break
                        }
                        x += 6
                    }
                    y += 6
                }
            }
            assertTrue("could not find the drawn word on screen", tapX >= 0f && tapY >= 0f)

            val downTime = SystemClock.uptimeMillis()
            scenario.onActivity {
                val v = it.findViewById<ReflowPageView>(ir.lexilens.reader.R.id.reflow_view)
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
                v.onTouchEvent(down)
                down.recycle()
            }
            SystemClock.sleep(800)
            scenario.onActivity {
                val v = it.findViewById<ReflowPageView>(ir.lexilens.reader.R.id.reflow_view)
                val up = MotionEvent.obtain(downTime, downTime + 800, MotionEvent.ACTION_UP, tapX, tapY, 0)
                v.onTouchEvent(up)
                up.recycle()
            }
            SystemClock.sleep(500)

            var shown: String? = null
            instrumentation.runOnMainSync { shown = findSheetWord() }
            assertTrue("word sheet did not open, shown=$shown", !shown.isNullOrBlank())
            assertEquals(
                "sheet must show the word that was pressed",
                targetLogical.filter { it.isLetterOrDigit() },
                RtlText.visualToLogical(shown!!).filter { it.isLetterOrDigit() },
            )
        } finally {
            scenario?.close()
            runCatching { app.db.deleteDocument(docId) }
            runCatching { pdf.delete() }
        }
    }

    private fun waitForText(scenario: ActivityScenario<ReaderActivity>) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val reflow = activity.findViewById<ReflowPageView>(ir.lexilens.reader.R.id.reflow_view)
                ready = reflow.visibility == View.VISIBLE && !reflow.content.isEmpty
            }
            if (ready) return
            SystemClock.sleep(250)
        }
        throw AssertionError("reflow text did not appear")
    }

    private fun findSheetWord(): String? {
        return runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)
            val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = field.get(instance) as List<View>
            views.firstNotNullOfOrNull {
                it.findViewById<TextView>(ir.lexilens.reader.R.id.word)?.text?.toString()
            }
        }.getOrNull()
    }
}
