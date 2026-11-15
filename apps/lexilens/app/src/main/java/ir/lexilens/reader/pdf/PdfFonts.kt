package ir.lexilens.reader.pdf

import android.content.Context
import android.graphics.Typeface
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONObject
import java.io.File

/** The face the PDF itself uses, ready to be handed to [android.text.StaticLayout]. */
data class PdfFontFace(
    val familyName: String?,
    val regular: Typeface?,
    val italic: Typeface?,
    val embedded: Boolean,
    val rtl: Boolean = false,
) {
    val hasFont: Boolean get() = regular != null

    companion object {
        val NONE = PdfFontFace(null, null, null, false)
    }
}

/**
 * Finds the font a PDF is written in and, when the file embeds a usable
 * TrueType/OpenType program, extracts it so reflow mode looks like the
 * original page instead of an unrelated system serif.
 *
 * Everything is best-effort: subset fonts without a character map, bare CFF
 * fonts and Type 1 fonts cannot be loaded by Android, so the caller falls back
 * to a family-name match (Vazirmatn, serif, sans) instead.
 */
object PdfFonts {

    private const val MAX_PAGES = 8
    private const val DIR_NAME = "pdf-fonts"

    fun load(context: Context, docId: Long, pdf: File): PdfFontFace {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val marker = File(dir, "$docId.json")
        val cached = readMarker(marker)
        if (cached != null) return cached
        val face = runCatching { inspect(pdf, dir, docId) }.getOrNull() ?: PdfFontFace.NONE
        if (face.familyName != null) {
            val regular = dir.listFiles()
                ?.firstOrNull { it.name.startsWith("pdf-$docId-regular.") }?.name
            val italic = dir.listFiles()
                ?.firstOrNull { it.name.startsWith("pdf-$docId-italic.") }?.name
            writeMarker(marker, face, regular, italic)
        }
        return face
    }

    // ------------------------------------------------------------- inspection

    private fun inspect(pdf: File, dir: File, docId: Long): PdfFontFace {
        val document = PDDocument.load(pdf)
        try {
            val collector = FontCollector()
            collector.startPage = 1
            collector.endPage = minOf(document.numberOfPages, MAX_PAGES)
            collector.sortByPosition = false
            collector.writeText(document, java.io.StringWriter())
            val candidates = collector.candidates()
            if (candidates.isEmpty()) return PdfFontFace.NONE

            // Body text is normally the most used regular (non-bold) face;
            // headings are heavy but rare, so they must not become the body.
            val base = candidates.filter { !it.italic && !it.bold }.maxByOrNull { it.weight }
                ?: candidates.filter { !it.italic }.maxByOrNull { it.weight }
                ?: candidates.maxByOrNull { it.weight }
                ?: return PdfFontFace.NONE
            val italic = (candidates
                .filter { it.italic && !it.bold && it.key != base.key }
                .maxByOrNull { it.weight }
                ?: candidates.filter { it.italic && it.key != base.key }.maxByOrNull { it.weight })

            val family = displayName(base.name)
            val regularFile = File(dir, "pdf-$docId-regular")
            val regular = extract(base, regularFile)
            val italicFile = File(dir, "pdf-$docId-italic")
            val italicFace = italic?.let { extract(it, italicFile) }

            val rtl = base.sample.any { it in '\u0590'..'\u08FF' }
            return if (regular != null) {
                PdfFontFace(family, regular, italicFace, embedded = true, rtl = rtl)
            } else {
                PdfFontFace(family, null, null, embedded = false, rtl = rtl)
            }
        } finally {
            runCatching { document.close() }
        }
    }

    private fun extract(candidate: FontCandidate, target: File): Typeface? {
        val instances = candidate.instances.sortedByDescending { it.weight }
        for (instance in instances) {
            val stream = embeddedStream(instance.font) ?: continue
            val extension = stream.second
            val bytes = runCatching { stream.first.toByteArray() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val file = File(target.parentFile, target.name + extension)
            val wrote = runCatching { file.writeBytes(bytes) }.isSuccess
            if (!wrote) continue
            val typeface = loadUsable(file, instance.sample)
            if (typeface != null) {
                // Remove stale files from an older extraction of the same PDF.
                target.parentFile?.listFiles()?.forEach { other ->
                    if (other.name.startsWith(target.name) && other != file) other.delete()
                }
                return typeface
            }
            file.delete()
        }
        return null
    }

    /**
     * Returns the embedded font program plus the file extension it should get.
     * Bare CFF (Type1C) programs are rejected because Android cannot load them.
     */
    private fun embeddedStream(font: PDFont): Pair<com.tom_roush.pdfbox.pdmodel.common.PDStream, String>? {
        val descriptor = descriptorOf(font) ?: return null
        runCatching { descriptor.fontFile2 }.getOrNull()?.let { return it to ".ttf" }
        val file3 = runCatching { descriptor.fontFile3 }.getOrNull() ?: return null
        val subtype = runCatching {
            file3.cosObject.getNameAsString(COSName.SUBTYPE)
        }.getOrNull()
        return when (subtype) {
            "OpenType" -> file3 to ".otf"
            else -> null
        }
    }

    private fun descriptorOf(font: PDFont): PDFontDescriptor? {
        runCatching { font.fontDescriptor }.getOrNull()?.let { return it }
        val descendant = (font as? PDType0Font)?.descendantFont ?: return null
        return runCatching { descendant.fontDescriptor }.getOrNull()
    }

    /**
     * A subset is only usable when Android can map the characters through a
     * Unicode cmap; otherwise every glyph would come from the system fallback.
     */
    private fun loadUsable(file: File, sample: String): Typeface? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (FontCmap.unicodeCoverage(bytes, sample) < 0.85f) return null
        return runCatching { Typeface.createFromFile(file) }.getOrNull()
    }

    // ----------------------------------------------------------------- marker

    private fun readMarker(marker: File): PdfFontFace? {
        if (!marker.exists()) return null
        return runCatching {
            val json = JSONObject(marker.readText())
            if (json.optInt("v", 0) < 1) return null
            val family = json.optString("family").takeIf { it.isNotBlank() } ?: return null
            val dir = marker.parentFile ?: return null
            val regularName = json.optString("regular").takeIf { it.isNotBlank() }
            val italicName = json.optString("italic").takeIf { it.isNotBlank() }
            val embedded = json.optBoolean("embedded", false)
            val rtl = json.optBoolean("rtl", false)
            val regular = regularName?.let { name ->
                val file = File(dir, name)
                if (file.exists()) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
            }
            val italic = italicName?.let { name ->
                val file = File(dir, name)
                if (file.exists()) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
            }
            if (embedded && regular == null) return null
            PdfFontFace(family, regular, italic, embedded, rtl)
        }.getOrNull()
    }

    private fun writeMarker(
        marker: File,
        face: PdfFontFace,
        regularFile: String?,
        italicFile: String?,
    ) {
        runCatching {
            val json = JSONObject()
            json.put("v", 1)
            json.put("family", face.familyName.orEmpty())
            json.put("embedded", face.embedded)
            json.put("rtl", face.rtl)
            regularFile?.let { json.put("regular", it) }
            italicFile?.let { json.put("italic", it) }
            val tmp = File(marker.parentFile, marker.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(marker)) {
                marker.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    // ----------------------------------------------------------- display name

    fun displayName(raw: String): String {
        val stripped = raw.replace(Regex("^[A-Z]{6}\\+"), "")
        val parts = stripped.split('-', ',', '_').filter { it.isNotBlank() }
        val styleWords = setOf(
            "regular", "book", "bold", "italic", "oblique", "medium",
            "light", "semibold", "black", "thin", "heavy", "condensed", "mt",
            "ps", "psmt", "w3", "w4", "w5", "w6", "w7",
        )
        val meaningful = parts.filter { it.lowercase() !in styleWords }
        val source = meaningful.ifEmpty { parts }.joinToString(" ")
        if (source.isBlank()) return stripped
        val words = source.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(' ')
            .filter { it.isNotBlank() }
        val cleaned = words.filter { it.lowercase() !in styleWords }
        return cleaned.ifEmpty { words }.joinToString(" ").trim()
    }

    // ------------------------------------------------------------- collection

    private class FontCollector : PDFTextStripper() {
        private val stats = LinkedHashMap<String, FontCandidate>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions == null) return
            for (position in textPositions) {
                val font = position.font ?: continue
                val name = runCatching { font.name }.getOrNull() ?: continue
                val key = name.replace(Regex("^[A-Z]{6}\\+"), "")
                val candidate = stats.getOrPut(key) { FontCandidate(key, name) }
                val instance = candidate.instance(font)
                val unicode = position.unicode.orEmpty()
                instance.weight += unicode.count { it.isLetterOrDigit() }
                for (ch in unicode) {
                    if (ch.isLetterOrDigit() && instance.sample.length < 80 &&
                        !instance.sample.contains(ch)
                    ) {
                        instance.sample += ch
                    }
                }
            }
        }

        fun candidates(): List<FontCandidate> =
            stats.values.filter { it.weight > 0 }.sortedByDescending { it.weight }
    }

    private class FontInstance(val font: PDFont) {
        var weight = 0
        var sample = ""
    }

    private class FontCandidate(val key: String, val name: String) {
        val instances = mutableListOf<FontInstance>()
        val weight: Int get() = instances.sumOf { it.weight }
        val sample: String get() = instances.joinToString("") { it.sample }.take(80)

        fun instance(font: PDFont): FontInstance =
            instances.firstOrNull { it.font === font }
                ?: FontInstance(font).also { instances.add(it) }

        val italic: Boolean
            get() {
                if (instances.any { isFontItalic(it.font) }) return true
                val lower = key.lowercase()
                return lower.contains("italic") || lower.contains("oblique")
            }

        val bold: Boolean
            get() {
                if (instances.any { isFontBold(it.font) }) return true
                val lower = key.lowercase()
                return lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
            }
    }

    private fun isFontItalic(font: PDFont): Boolean {
        val descriptor = descriptorOf(font) ?: return false
        if (runCatching { descriptor.isItalic }.getOrDefault(false)) return true
        val angle = runCatching { descriptor.italicAngle }.getOrNull() ?: 0f
        return angle != 0f && angle.isFinite()
    }

    private fun isFontBold(font: PDFont): Boolean {
        val descriptor = descriptorOf(font) ?: return false
        return runCatching { descriptor.isForceBold }.getOrDefault(false)
    }
}
