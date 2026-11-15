package ir.lexilens.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.ReflowContent
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.pdf.RtlText
import ir.lexilens.reader.reader.ReflowPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class ReflowHitAccuracyTest {

    private val width = 1080
    private val height = 1920

    @Test
    fun everyDrawnWordIsHitReportedAsItselfOnEnglishPdf() {
        val mismatches = check("en.pdf")
        assertTrue("English PDF word hit mismatches: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun everyDrawnWordIsHitReportedAsItselfOnPersianPdf() {
        val mismatches = check("fa.pdf")
        assertTrue("Persian PDF word hit mismatches: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun persianReflowIsLogicalNotMirrored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "order_fa.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fa.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val page = PdfTextExtractor(context, 999_002L, pdf).use { it.extract(0) }
            ?: error("no text")
        val content = ReflowText.build(page)
        val all = content.paragraphs.joinToString(" ") { it.text }
        val logicalTitle = RtlText.visualToLogical("ﮏﯾﺰیﻓ")
        assertTrue(
            "reflow text is not in logical order: ${content.paragraphs.firstOrNull()?.text}",
            all.contains(logicalTitle),
        )
    }

    private fun check(asset: String): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "hit_$asset")
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }
        val page: PageText = PdfTextExtractor(context, 999_001L, pdf).use {
            it.extract(0) ?: error("no text in $asset")
        }
        val content = ReflowText.build(page)
        assertTrue("$asset produced no reflow content", !content.isEmpty)

        val view = ReflowPageView(context).apply {
            setChromeVisible(false)
            textSizePercent = 100
            setContent(content)
        }
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val horizontalPadding = 22f * density
        val topPadding = 30f * density
        val textSize = 17f * scaledDensity
        val paragraphGap = textSize * 0.72f
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            this.textSize = textSize
        }
        val mismatches = mutableListOf<String>()
        var paragraphTop = 0f
        var checked = 0
        for ((pIndex, paragraph) in content.paragraphs.withIndex()) {
            val layout = StaticLayout.Builder
                .obtain(paragraph.text, 0, paragraph.text.length, paint, (width - 2f * horizontalPadding).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.22f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                .build()
            for (span in paragraph.words) {
                val token = page.words.getOrNull(span.wordIndex) ?: continue
                val drawn = paragraph.text.substring(span.start, span.end.coerceAtMost(paragraph.text.length))
                val expected = RtlText.visualToLogical(token.text)
                val same = drawn.equals(token.text, ignoreCase = true) ||
                    drawn.filter { it.isLetterOrDigit() } == token.text.filter { it.isLetterOrDigit() } ||
                    drawn.equals(expected, ignoreCase = true) ||
                    drawn.filter { it.isLetterOrDigit() } == expected.filter { it.isLetterOrDigit() }
                if (!same) {
                    mismatches += "span=${span.wordIndex} drawn=«$drawn» token=«${token.text}» logical=«$expected»"
                    continue
                }
                val startLine = layout.getLineForOffset(span.start.coerceIn(0, paragraph.text.length))
                val endLine = layout.getLineForOffset(
                    (span.end - 1).coerceIn(0, (paragraph.text.length - 1).coerceAtLeast(0))
                )
                for (line in startLine..endLine) {
                    val lineStart = layout.getLineStart(line)
                    val lineEnd = layout.getLineEnd(line)
                    val from = max(span.start, lineStart)
                    val to = min(span.end, lineEnd)
                    if (from >= to) continue
                    val x0 = min(layout.getPrimaryHorizontal(from), layout.getSecondaryHorizontal(from))
                    val x1 = max(layout.getPrimaryHorizontal(to), layout.getSecondaryHorizontal(to))
                    if (x1 - x0 < 4f) continue
                    val top = topPadding + paragraphTop + layout.getLineTop(line)
                    val bottom = topPadding + paragraphTop + layout.getLineBottom(line)
                    val x = horizontalPadding + (x0 + x1) / 2f
                    val y = (top + bottom) / 2f
                    if (y !in 0f..height.toFloat()) continue
                    val got = view.wordIndexAt(x, y)
                    checked++
                    if (got != span.wordIndex) {
                        mismatches += "p$pIndex span=${span.wordIndex} «${token.text}» at " +
                            "(${x.toInt()},${y.toInt()}) got=$got «${got?.let { page.words.getOrNull(it)?.text }}»"
                    }
                }
            }
            paragraphTop += layout.height + paragraphGap
        }
        assertTrue("$asset had too few checked spans ($checked)", checked > 20)
        return mismatches
    }
}
