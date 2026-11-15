package ir.lexilens.reader.pdf

/**
 * Minimal sfnt character-map reader.
 *
 * Android/FreeType only maps text through a Unicode cmap (platform 0 or
 * Windows platform 3, Unicode/BMP encodings). PDF producers often embed
 * subsets with a Macintosh (platform 1) cmap only — loading those files makes
 * every glyph fall back to the system font, and italic faces stop working.
 * This reader lets [PdfFonts] reject such files instead of silently degrading.
 */
internal object FontCmap {

    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_WINDOWS = 3
    private const val ENCODING_WINDOWS_UNICODE = 1
    private const val ENCODING_WINDOWS_FULL = 10

    private const val FORMAT_0 = 0
    private const val FORMAT_4 = 4
    private const val FORMAT_6 = 6
    private const val FORMAT_12 = 12

    /**
     * Share of the letter/digit characters in [sample] that this font can
     * actually map (0.0 … 1.0). Returns 0 when the file has no Unicode cmap.
     */
    fun unicodeCoverage(data: ByteArray, sample: String): Float {
        val chars = sample.toSet().filter { it.isLetterOrDigit() }
        if (chars.isEmpty()) return if (hasUnicodeMap(data)) 1f else 0f
        val subtables = unicodeSubtables(data)
        if (subtables.isEmpty()) return 0f
        var found = 0
        for (ch in chars) {
            val cp = ch.code
            if (subtables.any { glyphFor(data, it, cp) != 0 }) found++
        }
        return found.toFloat() / chars.size
    }

    fun hasUnicodeMap(data: ByteArray): Boolean = unicodeSubtables(data).isNotEmpty()

    // ------------------------------------------------------------------ tables

    private fun unicodeSubtables(data: ByteArray): List<Subtable> {
        val cmapOffset = tableOffset(data, "cmap") ?: return emptyList()
        if (cmapOffset + 4 > data.size) return emptyList()
        val count = u16(data, cmapOffset + 2) ?: return emptyList()
        val formatted = mutableListOf<Subtable>()
        for (i in 0 until count) {
            val record = cmapOffset + 4 + i * 8
            if (record + 8 > data.size) break
            val platform = u16(data, record) ?: continue
            val encoding = u16(data, record + 2) ?: continue
            val offset = u32(data, record + 4)?.toInt() ?: continue
            val unicode = platform == PLATFORM_UNICODE ||
                (platform == PLATFORM_WINDOWS &&
                    (encoding == ENCODING_WINDOWS_UNICODE || encoding == ENCODING_WINDOWS_FULL))
            if (!unicode) continue
            val at = cmapOffset + offset
            val format = u16(data, at) ?: continue
            if (format in setOf(FORMAT_0, FORMAT_4, FORMAT_6, FORMAT_12)) {
                formatted.add(Subtable(at, format))
            }
        }
        // Prefer the format that covers the most code points, but keep every
        // usable subtable: coverage checks try them in this order.
        return formatted.sortedByDescending {
            when (it.format) {
                FORMAT_12 -> 3
                FORMAT_4 -> 2
                FORMAT_6 -> 1
                else -> 0
            }
        }
    }

    private fun tableOffset(data: ByteArray, tag: String): Int? {
        if (data.size < 12) return null
        val version = u32(data, 0) ?: return null
        // 0x00010000 (TrueType), 'OTTO' (CFF), 'true', 'typ1'.
        val known = version == 0x00010000L ||
            version == 0x4F54544FL ||
            version == 0x74727565L ||
            version == 0x74797031L
        if (!known) return null
        val tables = u16(data, 4) ?: return null
        for (i in 0 until tables) {
            val record = 12 + i * 16
            if (record + 16 > data.size) return null
            val name = String(data, record, 4, Charsets.US_ASCII)
            if (name == tag) {
                return u32(data, record + 8)?.toInt()
            }
        }
        return null
    }

    // --------------------------------------------------------------- cmap kinds

    private fun glyphFor(data: ByteArray, table: Subtable, cp: Int): Int {
        if (cp < 0 || cp > 0x10FFFF) return 0
        return runCatching {
            when (table.format) {
                FORMAT_0 -> glyphFormat0(data, table.offset, cp)
                FORMAT_4 -> glyphFormat4(data, table.offset, cp)
                FORMAT_6 -> glyphFormat6(data, table.offset, cp)
                FORMAT_12 -> glyphFormat12(data, table.offset, cp)
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun glyphFormat0(data: ByteArray, at: Int, cp: Int): Int {
        if (cp > 255) return 0
        return u8(data, at + 6 + cp) ?: 0
    }

    private fun glyphFormat6(data: ByteArray, at: Int, cp: Int): Int {
        val first = u16(data, at + 6) ?: return 0
        val count = u16(data, at + 8) ?: return 0
        val index = cp - first
        if (index < 0 || index >= count) return 0
        return u16(data, at + 10 + index * 2) ?: 0
    }

    private fun glyphFormat4(data: ByteArray, at: Int, cp: Int): Int {
        if (cp > 0xFFFF) return 0
        val segCount = (u16(data, at + 6) ?: return 0) / 2
        if (segCount <= 0) return 0
        val endBase = at + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val rangeBase = deltaBase + segCount * 2
        for (i in 0 until segCount) {
            val end = u16(data, endBase + i * 2) ?: return 0
            if (cp > end) continue
            val start = u16(data, startBase + i * 2) ?: return 0
            if (cp < start) return 0
            val delta = u16(data, deltaBase + i * 2) ?: 0
            val rangeOffsetAt = rangeBase + i * 2
            val rangeOffset = u16(data, rangeOffsetAt) ?: return 0
            var glyph = if (rangeOffset == 0) {
                (cp + delta) and 0xFFFF
            } else {
                val glyphAt = rangeOffsetAt + rangeOffset + (cp - start) * 2
                u16(data, glyphAt) ?: return 0
            }
            if (rangeOffset != 0 && glyph != 0) glyph = (glyph + delta) and 0xFFFF
            return glyph
        }
        return 0
    }

    private fun glyphFormat12(data: ByteArray, at: Int, cp: Int): Int {
        val groups = u32(data, at + 12)?.toInt() ?: return 0
        for (i in 0 until groups) {
            val group = at + 16 + i * 12
            if (group + 12 > data.size) return 0
            val start = u32(data, group)?.toInt() ?: return 0
            val end = u32(data, group + 4)?.toInt() ?: return 0
            if (cp < start || cp > end) continue
            val startGlyph = u32(data, group + 8)?.toInt() ?: return 0
            val glyph = startGlyph + (cp - start)
            return if (glyph > 0) glyph else 0
        }
        return 0
    }

    // ----------------------------------------------------------------- reading

    private fun u8(data: ByteArray, at: Int): Int? =
        if (at in data.indices) data[at].toInt() and 0xFF else null

    private fun u16(data: ByteArray, at: Int): Int? {
        if (at < 0 || at + 2 > data.size) return null
        return ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    }

    private fun u32(data: ByteArray, at: Int): Long? {
        if (at < 0 || at + 4 > data.size) return null
        return ((data[at].toLong() and 0xFF) shl 24) or
            ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or
            (data[at + 3].toLong() and 0xFF)
    }

    private data class Subtable(val offset: Int, val format: Int)
}
