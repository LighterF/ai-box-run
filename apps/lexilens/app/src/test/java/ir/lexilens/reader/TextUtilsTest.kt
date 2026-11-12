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
}
