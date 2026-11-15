package ir.lexilens.reader

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.reader.PdfPageView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A right-to-left swipe must move forward; left-to-right must go back. */
@RunWith(AndroidJUnit4::class)
class PdfSwipeDirectionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val width = 1080
    private val height = 1920

    @Test
    fun rightToLeftAdvancesAndLeftToRightReturns() {
        val pdf = File(context.cacheDir, "swipe-pages.pdf")
        instrumentation.context.assets.open("pages3.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val renderer = PageRenderer(pdf)
        try {
            assertEquals(3, renderer.pageCount)
            var view: PdfPageView? = null
            instrumentation.runOnMainSync {
                val created = PdfPageView(context)
                created.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                created.layout(0, 0, width, height)
                created.setDocument(renderer, 3, 0)
                view = created
            }
            val pageView = view!!
            SystemClock.sleep(200)

            swipe(pageView, fromX = 920f, toX = 140f)
            SystemClock.sleep(700)
            instrumentation.waitForIdleSync()
            assertEquals("a right-to-left swipe should show the next page", 1, pageView.pageIndex)

            swipe(pageView, fromX = 140f, toX = 920f)
            SystemClock.sleep(700)
            instrumentation.waitForIdleSync()
            assertEquals("a left-to-right swipe should go back", 0, pageView.pageIndex)
        } finally {
            renderer.close()
            pdf.delete()
        }
    }

    private fun swipe(view: PdfPageView, fromX: Float, toX: Float) {
        val y = height / 2f
        val start = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            view.dispatchTouchEvent(
                MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, fromX, y, 0)
            )
        }
        for (step in 1..10) {
            val x = fromX + (toX - fromX) * step / 10f
            val time = start + step * 18L
            instrumentation.runOnMainSync {
                view.dispatchTouchEvent(
                    MotionEvent.obtain(start, time, MotionEvent.ACTION_MOVE, x, y, 0)
                )
            }
        }
        val end = start + 11 * 18L
        instrumentation.runOnMainSync {
            view.dispatchTouchEvent(
                MotionEvent.obtain(start, end, MotionEvent.ACTION_UP, toX, y, 0)
            )
        }
    }
}
