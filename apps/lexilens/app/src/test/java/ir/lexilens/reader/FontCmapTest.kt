package ir.lexilens.reader

import ir.lexilens.reader.pdf.FontCmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCmapTest {

    @Test
    fun readsWindowsUnicodeFormat4Table() {
        val data = sfnt(platform = 3, encoding = 1, subtable = format4(65, intArrayOf(1, 2)))
        assertEquals(1f, FontCmap.unicodeCoverage(data, "AB"), 0.001f)
        assertEquals(2f / 3f, FontCmap.unicodeCoverage(data, "ABC"), 0.001f)
        assertTrue(FontCmap.hasUnicodeMap(data))
    }

    @Test
    fun ignoresMacintoshOnlyCharacterMaps() {
        // PDF subsets frequently embed Mac Roman (platform 1) maps only; Android
        // cannot use them for text and would silently fall back to a system font.
        val data = sfnt(platform = 1, encoding = 0, subtable = format4(65, intArrayOf(1)))
        assertFalse(FontCmap.hasUnicodeMap(data))
        assertEquals(0f, FontCmap.unicodeCoverage(data, "A"), 0.001f)
    }

    @Test
    fun readsUnicodeFormat12Groups() {
        val data = sfnt(platform = 0, encoding = 4, subtable = format12(0x0645, 0x0645, 7))
        assertEquals(1f, FontCmap.unicodeCoverage(data, "م"), 0.001f)
        assertEquals(0f, FontCmap.unicodeCoverage(data, "A"), 0.001f)
    }

    @Test
    fun rejectsGarbageWithoutCrashing() {
        assertFalse(FontCmap.hasUnicodeMap(ByteArray(8)))
        assertFalse(FontCmap.hasUnicodeMap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        assertEquals(0f, FontCmap.unicodeCoverage(ByteArray(0), "A"), 0.001f)
    }

    // ------------------------------------------------------------- synthetic sfnt

    private fun sfnt(platform: Int, encoding: Int, subtable: ByteArray): ByteArray {
        val cmap = ByteArray(12 + subtable.size)
        putU16(cmap, 0, 0)
        putU16(cmap, 2, 1)
        putU16(cmap, 4, platform)
        putU16(cmap, 6, encoding)
        putU32(cmap, 8, 12)
        subtable.copyInto(cmap, 12)

        val data = ByteArray(28 + cmap.size)
        putU32(data, 0, 0x00010000)
        putU16(data, 4, 1)
        putU16(data, 6, 16)
        putU16(data, 8, 0)
        putU16(data, 10, 0)
        data[12] = 'c'.code.toByte()
        data[13] = 'm'.code.toByte()
        data[14] = 'a'.code.toByte()
        data[15] = 'p'.code.toByte()
        putU32(data, 20, 28)
        putU32(data, 24, cmap.size.toLong())
        cmap.copyInto(data, 28)
        return data
    }

    private fun format4(first: Int, glyphs: IntArray): ByteArray {
        val last = first + glyphs.size - 1
        val size = 24 + glyphs.size * 2
        val data = ByteArray(size)
        putU16(data, 0, 4)
        putU16(data, 2, size)
        putU16(data, 4, 0)
        putU16(data, 6, 2)
        putU16(data, 8, 2)
        putU16(data, 10, 0)
        putU16(data, 12, 0)
        putU16(data, 14, last)
        putU16(data, 16, 0)
        putU16(data, 18, first)
        putU16(data, 20, 0)
        // idRangeOffset points two bytes past itself, where the glyph array starts.
        putU16(data, 22, 2)
        for (i in glyphs.indices) putU16(data, 24 + i * 2, glyphs[i])
        return data
    }

    private fun format12(start: Int, end: Int, startGlyph: Long): ByteArray {
        val data = ByteArray(28)
        putU16(data, 0, 12)
        putU16(data, 2, 0)
        putU32(data, 4, 28)
        putU32(data, 8, 0)
        putU32(data, 12, 1)
        putU32(data, 16, start.toLong())
        putU32(data, 20, end.toLong())
        putU32(data, 24, startGlyph)
        return data
    }

    private fun putU16(data: ByteArray, at: Int, value: Int) {
        data[at] = ((value shr 8) and 0xFF).toByte()
        data[at + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(data: ByteArray, at: Int, value: Long) {
        data[at] = ((value shr 24) and 0xFF).toByte()
        data[at + 1] = ((value shr 16) and 0xFF).toByte()
        data[at + 2] = ((value shr 8) and 0xFF).toByte()
        data[at + 3] = (value and 0xFF).toByte()
    }
}
