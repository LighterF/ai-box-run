package ir.lexilens.reader

import ir.lexilens.reader.util.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {
    @Test
    fun normalizeStripsPunctuationAndCase() {
        assertEquals("reader", TextUtils.normalizeWord("\u201CReader,\u201D"))
        assertEquals("don't", TextUtils.normalizeWord("Don\u2019t"))
    }

    @Test
    fun possessiveIsCleaned() {
        assertEquals("reader", TextUtils.cleanToken("Reader's"))
    }

    @Test
    fun morphologyCoversCommonForms() {
        assertTrue(TextUtils.morphologyCandidates("running").contains("run"))
        assertTrue(TextUtils.morphologyCandidates("studies").contains("study"))
        assertTrue(TextUtils.morphologyCandidates("walked").contains("walk"))
    }

    @Test
    fun csvQuotesCommasAndQuotes() {
        assertEquals("\"a,b\"", TextUtils.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", TextUtils.csvEscape("say \"hi\""))
    }

    @Test
    fun persianNumbers() {
        assertEquals("۱۲۳", TextUtils.faNumber(123))
    }

    @Test
    fun shortExcerptIsUnchanged() {
        val line = "The quick brown fox jumps over the lazy dog."
        assertEquals(line, TextUtils.excerptAround(line, "quick", 120))
    }

    @Test
    fun longLineIsCappedAroundWord() {
        val line = (1..80).joinToString(" ") { "word$it" } + " target " +
            (81..160).joinToString(" ") { "word$it" }
        val out = TextUtils.excerptAround(line, "target", 120)
        assertTrue(out.contains("target"))
        assertTrue("excerpt too long: ${out.length}", out.length <= 150)
    }

    @Test
    fun excerptKeepsSentenceBoundaries() {
        val line = "First sentence is here. The target word sits in this second sentence. " +
            "Third sentence follows with more text that should be dropped."
        val out = TextUtils.excerptAround(line, "target", 120)
        assertTrue(out.contains("target"))
        assertTrue(out.contains("second sentence"))
        assertTrue(!out.contains("Third sentence"))
    }
}
