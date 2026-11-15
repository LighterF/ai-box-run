package ir.lexilens.reader

import android.os.SystemClock
import android.view.MotionEvent
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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReflowScrollFeelTest {

    @Test
    fun subPixelDragsScrollAndEdgeSpringsBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pdf = File(context.cacheDir, "scroll_en.pdf")
        instrumentation.context.assets.open("en.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val page = PdfTextExtractor(context, 992_001L, pdf).use { it.extract(0) }!!
        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            textSizePercent = 180
            setContent(ReflowText.build(page))
        }
        instrumentation.runOnMainSync {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 800, 700)
        }

        fun event(action: Int, x: Float, y: Float, time: Long) =
            MotionEvent.obtain(0, time, action, x, y, 0)
        val t0 = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 400f, 600f, t0)) }
        var time = t0 + 16
        // Cross the touch slop first so the drag is live.
        instrumentation.runOnMainSync { view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 580f, time)) }
        val afterSlop = view.currentScrollOffset()
        // Ten 0.5 px moves would all truncate to zero with an Int offset.
        for (i in 1..10) {
            time += 16
            instrumentation.runOnMainSync {
                view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 580f - i * 0.5f, time))
            }
        }
        val afterTiny = view.currentScrollOffset()
        assertTrue(
            "sub-pixel drags must keep scrolling, got $afterSlop → $afterTiny",
            afterTiny - afterSlop > 2f,
        )

        // Drag past the top edge: rubber band, then release and let it settle.
        time += 16
        instrumentation.runOnMainSync {
            view.onTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 600f + 600f, time))
        }
        val overscrolled = view.currentScrollOffset()
        assertTrue("dragging down at the top should overscroll, got $overscrolled", overscrolled < 0f)
        assertTrue("overscroll must be damped", overscrolled > -200f)
        time += 16
        instrumentation.runOnMainSync {
            view.onTouchEvent(event(MotionEvent.ACTION_UP, 400f, 1200f, time))
        }
        val deadline = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < deadline && abs(view.currentScrollOffset()) > 0.5f) {
            instrumentation.runOnMainSync { view.computeScroll() }
            SystemClock.sleep(16)
        }
        assertEquals(0f, view.currentScrollOffset(), 0.5f)
    }
}
