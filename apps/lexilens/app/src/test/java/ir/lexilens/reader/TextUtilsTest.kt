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
    fun parsesPersianArabicAndLatinDigits() {
        assertEquals(250, TextUtils.parseNumber("۲۵۰"))
        assertEquals(250, TextUtils.parseNumber("٢٥٠"))
        assertEquals(250, TextUtils.parseNumber("250"))
        assertEquals(1200, TextUtils.parseNumber("۱٬۲۰۰"))
        assertEquals(0, TextUtils.parseNumber("۰"))
        assertEquals(null, TextUtils.parseNumber(""))
        assertEquals(null, TextUtils.parseNumber("دو"))
        assertEquals(null, TextUtils.parseNumber("12a"))
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

    @Test
    fun sentenceAroundUsesPunctuationAndKeepsWordAtLineEnd() {
        val text = "He opened the old door. The target word was waiting at the end. " +
            "Nobody expected it."
        val out = TextUtils.sentenceAround(text, "target", 200)
        assertEquals("The target word was waiting at the end.", out)
    }

    @Test
    fun sentenceAroundContinuesWhenWordIsLastWordOfLine() {
        val text = "The boy looked at the target. Then he ran home."
        val out = TextUtils.sentenceAround(text, "target", 200)
        assertEquals("The boy looked at the target.", out)
    }

    @Test
    fun sentenceAroundDoesNotBreakOnAbbreviationsOrDecimals() {
        val text = "Mr. Smith paid 3.50 dollars for the target book. Then he left."
        val out = TextUtils.sentenceAround(text, "target", 200)
        assertEquals("Mr. Smith paid 3.50 dollars for the target book.", out)
    }

    @Test
    fun sentenceAroundCapsLongSentenceWithoutCuttingWord() {
        val text = "First sentence. " + (1..80).joinToString(" ") { "word$it" } +
            " target " + (81..160).joinToString(" ") { "word$it" } + ". Last sentence."
        val out = TextUtils.sentenceAround(text, "target", 120)
        assertTrue(out.contains("target"))
        assertTrue("excerpt too long: ${out.length}", out.length <= 150)
        assertTrue(out.startsWith("…") || out.startsWith("word"))
    }

    @Test
    fun sentenceAroundKeepsQuotesAndEllipsisEnding() {
        val text = "She whispered, \"Do not touch the target.\" Then she left."
        val out = TextUtils.sentenceAround(text, "target", 200)
        assertEquals("She whispered, \"Do not touch the target.\"", out)
    }

    @Test
    fun legacyBlankOrClippedContextNeedsRefresh() {
        assertTrue(TextUtils.needsContextRefresh(""))
        assertTrue(TextUtils.needsContextRefresh("… the target word was here …"))
        assertTrue(TextUtils.needsContextRefresh("The target word was here …"))
    }

    @Test
    fun currentFullContextDoesNotNeedRefresh() {
        assertTrue(!TextUtils.needsContextRefresh("The target word was here."))
        assertTrue(!TextUtils.needsContextRefresh("… " + "x".repeat(400) + " …"))
    }
}
