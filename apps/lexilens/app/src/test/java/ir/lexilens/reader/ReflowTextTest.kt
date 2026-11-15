package ir.lexilens.reader

import ir.lexilens.reader.pdf.LineBox
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.ReflowText
import ir.lexilens.reader.pdf.WordBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowTextTest {

    @Test
    fun joinsLinesOfOneParagraphWithSpaces() {
        val page = page(
            Spec("The quick brown fox", 10f, 10f),
            Spec("jumps over the lazy dog.", 10f, 26f),
        )

        val content = ReflowText.build(page)

        assertEquals(1, content.paragraphs.size)
        assertEquals(
            "The quick brown fox jumps over the lazy dog.",
            content.paragraphs[0].text,
        )
    }

    @Test
    fun joinsHyphenatedLineBreakWithoutHyphen() {
        val page = page(
            Spec("The transfor-", 10f, 10f),
            Spec("mation worked.", 10f, 26f),
        )

        val content = ReflowText.build(page)

        assertEquals("The transformation worked.", content.paragraphs[0].text)
    }

    @Test
    fun keepsPunctuationFromThePdfLines() {
        val page = page(Spec("“Really?” she asked: yes, it is!", 10f, 10f))

        val content = ReflowText.build(page)

        assertEquals("“Really?” she asked: yes, it is!", content.paragraphs[0].text)
    }

    @Test
    fun breaksParagraphWhenVerticalGapIsLarge() {
        val page = page(
            Spec("First paragraph stays here.", 10f, 10f),
            Spec("Second paragraph starts here.", 10f, 60f),
        )

        val content = ReflowText.build(page)

        assertEquals(2, content.paragraphs.size)
    }

    @Test
    fun breaksParagraphOnFirstLineIndent() {
        val page = page(
            Spec("First paragraph fills the whole line and continues", 10f, 10f),
            Spec("to here.", 10f, 26f),
            Spec("Second paragraph is indented.", 45f, 42f),
        )

        val content = ReflowText.build(page)

        assertEquals(2, content.paragraphs.size)
        assertEquals("Second paragraph is indented.", content.paragraphs[1].text)
    }

    @Test
    fun dropsPageNumberAtTheBottom() {
        val page = page(
            Spec("Real content of the page.", 10f, 100f),
            Spec("42", 290f, 780f),
            pageHeight = 800f,
        )

        val content = ReflowText.build(page)

        assertEquals(1, content.paragraphs.size)
        assertEquals("Real content of the page.", content.paragraphs[0].text)
    }

    @Test
    fun readsLeftColumnBeforeRightColumn() {
        val page = page(
            Spec("Left one", 10f, 10f),
            Spec("Left two", 10f, 26f),
            Spec("Left three", 10f, 42f),
            Spec("Left four", 10f, 58f),
            Spec("Right one", 320f, 10f),
            Spec("Right two", 320f, 26f),
            Spec("Right three", 320f, 42f),
            Spec("Right four", 320f, 58f),
            pageWidth = 600f,
        )

        val content = ReflowText.build(page)

        assertTrue(content.paragraphs.size >= 2)
        assertTrue(content.paragraphs.first().text.startsWith("Left one"))
        assertTrue(content.paragraphs.last().text.startsWith("Right one"))
    }

    @Test
    fun startsNewParagraphForEveryBullet() {
        val page = page(
            Spec("Shopping list:", 10f, 10f),
            Spec("• apples", 10f, 26f),
            Spec("• pears", 10f, 42f),
        )

        val content = ReflowText.build(page)

        assertEquals(3, content.paragraphs.size)
        assertEquals("• apples", content.paragraphs[1].text)
        assertEquals("• pears", content.paragraphs[2].text)
    }

    @Test
    fun mapsCharactersBackToTheCorrectWord() {
        val page = page(
            Spec("Harry looked at the", 10f, 10f),
            Spec("odd package.", 10f, 26f),
        )
        val content = ReflowText.build(page)
        val paragraph = content.paragraphs[0]
        val offset = paragraph.text.indexOf("package")

        val index = paragraph.wordAt(offset)

        assertEquals("package", page.words[index!!].text)
    }

    @Test
    fun punctuationBetweenWordsResolvesToNearestWord() {
        val page = page(Spec("Hello, world!", 10f, 10f))
        val paragraph = ReflowText.build(page).paragraphs[0]
        val comma = paragraph.text.indexOf(',')

        val left = paragraph.wordAt(comma)
        val right = paragraph.wordAt(comma + 1)

        assertEquals("Hello", page.words[left!!].text)
        assertEquals("world", page.words[right!!].text)
    }

    @Test
    fun keepsItalicWordsWhenBuildingParagraphs() {
        val page = page(Spec("I want to read this book.", 10f, 10f, italic = setOf("I")))

        val content = ReflowText.build(page)
        val paragraph = content.paragraphs[0]
        val iWord = page.words.indexOfFirst { it.text == "I" }
        val wantWord = page.words.indexOfFirst { it.text == "want" }
        val italicSpan = paragraph.words.first { it.wordIndex == iWord }
        val plainSpan = paragraph.words.first { it.wordIndex == wantWord }

        assertTrue(italicSpan.italic)
        assertEquals("I", paragraph.text.substring(italicSpan.start, italicSpan.end))
        assertFalse(plainSpan.italic)
    }

    @Test
    fun emptyPageProducesEmptyContent() {
        val page = PageText(600f, 800f, emptyList(), emptyList())

        assertTrue(ReflowText.build(page).isEmpty)
    }

    private data class Spec(
        val text: String,
        val x: Float,
        val y: Float,
        val italic: Set<String> = emptySet(),
    )

    private fun page(
        vararg specs: Spec,
        pageWidth: Float = 600f,
        pageHeight: Float = 800f,
    ): PageText {
        val words = mutableListOf<WordBox>()
        val lines = mutableListOf<LineBox>()
        specs.forEachIndexed { lineIndex, spec ->
            val startWord = words.size
            var x = spec.x
            val height = 12f
            spec.text.split(' ').filter { it.isNotEmpty() }.forEach { raw ->
                val token = raw.trim { ch ->
                    ch.isWhitespace() || ch in ".,;:!?\"'“”‘’()[]{}<>،؛…—–-«»"
                }
                if (token.isNotEmpty()) {
                    val width = (token.length * 6f + 4f).coerceAtLeast(7f)
                    words.add(
                        WordBox(
                            token,
                            x,
                            spec.y,
                            width,
                            height,
                            lineIndex,
                            italic = token in spec.italic,
                        )
                    )
                    x += width + 4f
                } else {
                    x += 5f
                }
            }
            val endWord = words.size - 1
            lines.add(
                LineBox(
                    text = spec.text,
                    x = spec.x,
                    y = spec.y,
                    w = (x - spec.x).coerceAtLeast(1f),
                    h = height,
                    wordStart = startWord,
                    wordEnd = endWord,
                )
            )
        }
        return PageText(pageWidth, pageHeight, words, lines)
    }
}
