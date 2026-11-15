package ir.lexilens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.LineBox
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReflowPageViewTest {

    private val width = 1080
    private val height = 1920

    @Test
    fun drawsParagraphsAndHitsRealWords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = samplePage()
        val content = ReflowText.build(page)
        assertFalse(content.isEmpty)

        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = 120
            setContent(content)
        }
        layout(view)

        val hits = scanHits(view)
        assertTrue("the rendered page should contain hit targets", hits.isNotEmpty())
        val packageIndex = page.words.indexOfFirst { it.text == "package" }
        assertTrue("word from the second PDF line should be reachable", hits.containsKey(packageIndex))
        assertTrue(hits.keys.all { it in page.words.indices })

        val bitmap = draw(view)
        assertTrue("text should paint ink on the page", inkPixels(bitmap, Color.WHITE) > 10_000)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))

        val preview = File(context.filesDir, "reflow_preview.png")
        preview.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(preview.length() > 10_000)
        bitmap.recycle()
    }

    @Test
    fun changingTextSizeOnTheSameViewRequestsARedraw() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = 100
            setContent(ReflowText.build(samplePage()))
        }
        layout(view)
        draw(view).recycle()
        assertFalse("a just-drawn view should not be dirty", view.isDirty)

        view.textSizePercent = 140

        assertTrue("changing the text size must schedule a new frame", view.isDirty)
        assertEquals(140, view.textSizePercent)
    }

    @Test
    fun textSizeChangesTheAmountOfSpaceUsed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val content = ReflowText.build(samplePage())

        val small = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = ReflowPageView.MIN_TEXT_PERCENT
            setContent(content)
        }
        layout(small)
        val smallInk = inkPixels(draw(small), Color.WHITE)

        val large = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = ReflowPageView.MAX_TEXT_PERCENT
            setContent(content)
        }
        layout(large)
        val largeInk = inkPixels(draw(large), Color.WHITE)

        assertTrue("260% text should paint more ink than 65%", largeInk > smallInk)
        assertEquals(ReflowPageView.MAX_TEXT_PERCENT, large.textSizePercent)
    }

    @Test
    fun appliesCustomBackgroundAndTextColours() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = 0xFF102030.toInt()
            pageTextColor = 0xFFF0E0D0.toInt()
            setContent(ReflowText.build(samplePage()))
        }
        layout(view)
        val bitmap = draw(view)

        assertEquals(0xFF102030.toInt(), bitmap.getPixel(0, 0))
        assertTrue(inkPixels(bitmap, 0xFF102030.toInt()) > 5_000)
        bitmap.recycle()
    }

    @Test
    fun longPressReportsTheWordUnderTheFinger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = samplePage()
        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            pageBackground = Color.WHITE
            pageTextColor = Color.BLACK
            textSizePercent = 130
            setContent(ReflowText.build(page))
        }
        layout(view)
        val point = firstHitPoint(view)
        assertNotNull(point)

        val latch = CountDownLatch(1)
        var reported = -1
        view.listener = object : ReflowPageView.Listener {
            override fun onWordLongPress(wordIndex: Int, viewX: Float, viewY: Float) {
                reported = wordIndex
                latch.countDown()
            }

            override fun onLongPressMiss() = latch.countDown()
            override fun onTap() = Unit
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            point!!.first,
            point.second,
            0,
        )
        instrumentation.runOnMainSync { view.onTouchEvent(down) }
        Thread.sleep(700)
        instrumentation.waitForIdleSync()
        assertTrue("long press callback should fire", latch.await(2, TimeUnit.SECONDS))
        assertTrue("reported word index must be valid", reported in page.words.indices)

        val up = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            point.first,
            point.second,
            0,
        )
        instrumentation.runOnMainSync { view.onTouchEvent(up) }
        down.recycle()
        up.recycle()
    }

    private fun layout(view: ReflowPageView) {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun draw(view: ReflowPageView): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun scanHits(view: ReflowPageView): Map<Int, Int> {
        val hits = HashMap<Int, Int>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = view.wordIndexAt(x.toFloat(), y.toFloat())
                if (index != null) hits[index] = (hits[index] ?: 0) + 1
                x += 15
            }
            y += 15
        }
        return hits
    }

    private fun firstHitPoint(view: ReflowPageView): Pair<Float, Float>? {
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (view.wordIndexAt(x.toFloat(), y.toFloat()) != null) {
                    return x.toFloat() to y.toFloat()
                }
                x += 9
            }
            y += 9
        }
        return null
    }

    private fun inkPixels(bitmap: Bitmap, background: Int): Int {
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != background) count++
                x += 2
            }
            y += 2
        }
        return count
    }

    private fun samplePage(): PageText {
        val specs = listOf(
            "The quick brown fox jumps over the lazy dog. " to 10f,
            "Harry looked at the odd package on the table " to 10f,
            "and wondered who had sent it to him. " to 10f,
            "It was wrapped in brown paper and tied with string." to 10f,
        )
        val words = mutableListOf<WordBox>()
        val lines = mutableListOf<LineBox>()
        specs.forEachIndexed { lineIndex, (text, x) ->
            val start = words.size
            var cursor = x
            text.trim().split(' ').filter { it.isNotEmpty() }.forEach { raw ->
                val token = raw.trim { ch -> ch.isWhitespace() || ch in ".,;:!?\"'“”‘’()[]{}<>،؛…—–-«»" }
                if (token.isNotEmpty()) {
                    val w = (token.length * 9f + 6f).coerceAtLeast(8f)
                    words.add(WordBox(token, cursor, 20f + lineIndex * 44f, w, 30f, lineIndex))
                    cursor += w + 6f
                }
            }
            lines.add(
                LineBox(
                    text = text.trim(),
                    x = x,
                    y = 20f + lineIndex * 44f,
                    w = (cursor - x).coerceAtLeast(1f),
                    h = 30f,
                    wordStart = start,
                    wordEnd = words.size - 1,
                )
            )
        }
        return PageText(612f, 792f, words, lines)
    }
}
