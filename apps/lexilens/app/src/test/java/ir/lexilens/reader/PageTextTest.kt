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
    fun sentenceStartsBeforeTheLineThatContainsTheWord() {
        val page = buildPage(
            "At school, Harry had no one. Everybody knew that Dudley's gang hated that",
            "odd Harry Potter in his baggy old clothes.",
        )
        val odd = page.words.first { it.text == "odd" }
        val span = page.sentenceSpanFor(odd)
        assertEquals(
            "Everybody knew that Dudley's gang hated that odd Harry Potter in his baggy old clothes.",
            span?.text,
        )
        assertEquals(0, span?.startLine)
        assertEquals("Everybody", page.words[span!!.slices.first().firstWord].text)
    }

    @Test
    fun sentenceDoesNotIncludeNeighbouringSentencesOnTheSameLine() {
        val page = buildPage("First sentence. The target word ends here. Next sentence.")
        val target = page.words.first { it.text == "target" }
        val span = page.sentenceSpanFor(target)
        assertEquals("The target word ends here.", span?.text)
        assertEquals("The", page.words[span!!.slices.first().firstWord].text)
        assertEquals("here.", page.words[span.slices.last().lastWord].text)
    }

    @Test
    fun sentenceContinuesAcrossLineWhenPunctuationIsMissing() {
        val page = buildPage(
            "He opened the old door",
            "and walked in without a word.",
        )
        val door = page.words.first { it.text == "door" }
        val span = page.sentenceSpanFor(door)
        assertEquals("He opened the old door and walked in without a word.", span?.text)
        assertEquals(0, span?.startLine)
        assertEquals(1, span?.endLine)
    }

    @Test
    fun mapsNormalizedPointsForRotation() {
        val text = PageText(200f, 100f, emptyList(), emptyList(), rotation = 90)
        val (x, y) = text.mapFromPage(0.25f, 0.5f)
        assertEquals(100f, x, 0.01f)
        assertEquals(75f, y, 0.01f)
    }
}

private fun buildPage(vararg texts: String): PageText {
    val words = mutableListOf<WordBox>()
    val lines = mutableListOf<LineBox>()
    texts.forEachIndexed { lineIndex, text ->
        val start = words.size
        var x = 10f
        text.split(' ').forEach { token ->
            val w = (token.length * 6f + 4f).coerceAtLeast(7f)
            words.add(WordBox(token, x, 10f + lineIndex * 20f, w, 12f, lineIndex))
            x += w + 4f
        }
        lines.add(
            LineBox(
                text = text,
                x = 10f,
                y = 10f + lineIndex * 20f,
                w = x - 10f,
                h = 12f,
                wordStart = start,
                wordEnd = words.size - 1,
            )
        )
    }
    return PageText(600f, 80f, words, lines)
}
