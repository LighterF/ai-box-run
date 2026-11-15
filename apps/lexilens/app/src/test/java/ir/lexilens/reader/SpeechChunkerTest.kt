package ir.lexilens.reader

import ir.lexilens.reader.util.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun commaAndSentenceEndGetDifferentPauses() {
        val chunks = SpeechChunker.chunks("Hello, world. How are you?")
        assertEquals(listOf("Hello,", "world.", "How are you?"), chunks.map { it.text })
        assertEquals(170L, chunks[0].pauseAfterMs)
        assertEquals(380L, chunks[1].pauseAfterMs)
        assertEquals(0L, chunks[2].pauseAfterMs)
    }

    @Test
    fun abbreviationsAndDecimalsDoNotSplit() {
        val chunks = SpeechChunker.chunks("Mr. Smith paid 3.50 dollars, then left.")
        assertEquals(2, chunks.size)
        assertEquals("Mr. Smith paid 3.50 dollars,", chunks[0].text)
        assertEquals("then left.", chunks[1].text)
    }

    @Test
    fun longTextIsSplitWithoutLosingWords() {
        val text = (1..80).joinToString(" ") { "word$it" }
        val chunks = SpeechChunker.chunks(text, 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 120 })
        assertEquals(text, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun paragraphGetsExtraPause() {
        val chunks = SpeechChunker.chunks("First.\n\nSecond.")
        assertEquals(listOf("First.", "Second."), chunks.map { it.text })
        assertEquals(550L, chunks[0].pauseAfterMs)
        assertEquals(0L, chunks[1].pauseAfterMs)
    }

    @Test
    fun quotesStayWithTheirSentence() {
        val chunks = SpeechChunker.chunks("She said, \"Stop!\" Then left.")
        assertEquals("\"Stop!\"", chunks[1].text)
        assertEquals(430L, chunks[1].pauseAfterMs)
    }
}
