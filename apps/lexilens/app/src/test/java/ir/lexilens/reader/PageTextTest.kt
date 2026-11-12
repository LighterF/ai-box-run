package ir.lexilens.reader

import ir.lexilens.reader.pdf.LineBox
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.WordBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PageTextTest {
    private val words = listOf(
        WordBox("The", 10f, 10f, 30f, 12f, 0),
        WordBox("reader", 50f, 10f, 60f, 12f, 0),
        WordBox("works", 10f, 40f, 50f, 12f, 1),
    )
    private val lines = listOf(
        LineBox("The reader", 10f, 10f, 100f, 12f, 0, 1),
        LineBox("works", 10f, 40f, 50f, 12f, 2, 2),
    )

    @Test
    fun hitFindsWordAndLine() {
        val text = PageText(200f, 200f, words, lines)
        val hit = text.hit(55f, 16f)
        assertNotNull(hit)
        assertEquals("reader", hit!!.first.text)
        assertEquals("The reader", hit.second.text)
    }

    @Test
    fun nearestWordHelpsImprecisePress() {
        val text = PageText(200f, 200f, words, lines)
        val hit = text.hit(70f, 20f)
        assertNotNull(hit)
        assertEquals("reader", hit!!.first.text)
    }

    @Test
    fun mapsNormalizedPointsForRotation() {
        val text = PageText(200f, 100f, emptyList(), emptyList(), rotation = 90)
        val (x, y) = text.mapFromPage(0.25f, 0.5f)
        assertEquals(100f, x, 0.01f)
        assertEquals(75f, y, 0.01f)
    }
}
