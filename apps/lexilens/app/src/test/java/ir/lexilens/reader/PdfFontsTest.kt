package ir.lexilens.reader

import ir.lexilens.reader.pdf.PdfFonts
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfFontsTest {

    @Test
    fun dropsSubsetPrefixAndStyleSuffixFromFontName() {
        assertEquals("Deja Vu Serif", PdfFonts.displayName("AAAAAA+DejaVuSerif-Bold"))
        assertEquals("Vazirmatn", PdfFonts.displayName("BAAAAA+Vazirmatn-Regular"))
        assertEquals("IRANSans", PdfFonts.displayName("CAAAAA+IRANSans"))
    }

    @Test
    fun keepsMeaningfulWordsInFontName() {
        assertEquals("Times New Roman", PdfFonts.displayName("ABCDEF+TimesNewRomanPSMT"))
    }
}
