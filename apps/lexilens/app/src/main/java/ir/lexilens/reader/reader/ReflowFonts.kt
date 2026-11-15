package ir.lexilens.reader.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import ir.lexilens.reader.R
import ir.lexilens.reader.pdf.FontCmap
import ir.lexilens.reader.pdf.PdfFontFace
import java.io.File

/**
 * One selectable face in the reflow appearance sheet. [id] is persisted in
 * [ir.lexilens.reader.data.Prefs]; [italicTypeface] is the real italic cut when
 * the PDF (or a bundled family) has one, otherwise null and the reader slants
 * the base face for italic words.
 */
data class ReflowFontOption(
    val id: String,
    val label: String,
    val subtitle: String,
    val typeface: Typeface,
    val italicTypeface: Typeface? = null,
    val removable: Boolean = false,
)

/**
 * Bundled faces plus fonts the user imported from the device. The default
 * option mirrors the PDF's own font family, which [ir.lexilens.reader.pdf.PdfFonts]
 * extracts when the file embeds a usable program.
 */
object ReflowFonts {

    const val ID_PDF = "pdf"
    const val ID_VAZIRMATN = "vazirmatn"
    const val ID_SERIF = "serif"
    const val ID_SANS = "sans"
    const val ID_MONO = "mono"

    private const val IMPORT_PREFIX = "imported:"
    private const val IMPORT_DIR = "reflow-fonts"
    private const val MAX_IMPORT_BYTES = 40L * 1024 * 1024

    private val persianHints = listOf(
        "vazir", "nazanin", "naskh", "iransans", "yekan", "sahel", "shabnam",
        "samim", "parastoo", "gandom", "mitra", "noto naskh", "arabic",
        "far", "dastnevis", "nastaligh", "nastaliq", "titr", "lotus", "zar",
    )
    private val serifHints = listOf(
        "times", "serif", "georgia", "palatino", "garamond", "book", "roman",
        "cambria", "baskerville", "didot",
    )
    private val sansHints = listOf(
        "arial", "helvet", "sans", "calibri", "verdana", "tahoma", "segoe",
        "futura", "gill", "franklin", "roboto", "open sans", "lato",
    )

    fun options(context: Context, pdfFace: PdfFontFace?): List<ReflowFontOption> {
        val list = mutableListOf<ReflowFontOption>()
        list += pdfOption(context, pdfFace)
        list += vazirmatnOption(context)
        list += ReflowFontOption(
            id = ID_SERIF,
            label = "سریف",
            subtitle = "ظاهر کتابی",
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
            italicTypeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC),
        )
        list += ReflowFontOption(
            id = ID_SANS,
            label = "ساده",
            subtitle = "بدون تزئین",
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
            italicTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC),
        )
        list += ReflowFontOption(
            id = ID_MONO,
            label = "مونو",
            subtitle = "عرض ثابت",
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
            italicTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
        )
        list += importedOptions(context)
        return list
    }

    fun resolve(context: Context, id: String, pdfFace: PdfFontFace?): ReflowFontOption {
        val all = options(context, pdfFace)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    private fun pdfOption(context: Context, face: PdfFontFace?): ReflowFontOption {
        val family = face?.familyName?.takeIf { it.isNotBlank() }
        val embedded = face?.regular
        val subtitle = when {
            family == null -> "پیش‌فرض برنامه"
            embedded != null -> "$family · از خود فایل"
            else -> "$family · جایگزین"
        }
        return ReflowFontOption(
            id = ID_PDF,
            label = "فونت PDF",
            subtitle = subtitle,
            typeface = embedded ?: mappedFamily(context, family, face?.rtl == true),
            italicTypeface = face?.italic,
        )
    }

    private fun mappedFamily(context: Context, family: String?, rtl: Boolean): Typeface {
        if (family == null) return if (rtl) vazirmatn(context) else Typeface.SERIF
        val name = family.lowercase()
        if (persianHints.any { name.contains(it) }) return vazirmatn(context)
        if (serifHints.any { name.contains(it) }) return Typeface.SERIF
        if (sansHints.any { name.contains(it) }) return Typeface.SANS_SERIF
        return if (rtl) vazirmatn(context) else Typeface.SERIF
    }

    private fun vazirmatnOption(context: Context) = ReflowFontOption(
        id = ID_VAZIRMATN,
        label = "وزیرمتن",
        subtitle = "فونت فارسی",
        typeface = vazirmatn(context),
    )

    private fun vazirmatn(context: Context): Typeface =
        ResourcesCompat.getFont(context, R.font.vazirmatn_regular)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    // --------------------------------------------------------------- imported

    fun importDir(context: Context): File =
        File(context.filesDir, IMPORT_DIR).apply { mkdirs() }

    fun importedOptions(context: Context): List<ReflowFontOption> {
        val dir = importDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in FILE_TYPES }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file -> optionFor(file) }
            .orEmpty()
    }

    private fun optionFor(file: File): ReflowFontOption? {
        val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        return ReflowFontOption(
            id = IMPORT_PREFIX + file.name,
            label = file.nameWithoutExtension.replace('_', ' ').replace('-', ' '),
            subtitle = "فونت اضافه‌شده",
            typeface = typeface,
            removable = true,
        )
    }

    /**
     * Copies a picked font into app storage, verifies Android can actually
     * render with it, and returns the new option. Returns null for files that
     * are not loadable fonts, so a wrong pick cannot break the reader.
     */
    fun importFrom(context: Context, uri: Uri): ReflowFontOption? {
        val resolver = context.contentResolver
        val name = displayName(context, uri) ?: "font-${System.currentTimeMillis()}"
        val stem = name.substringBeforeLast('.').ifBlank { "font-${System.currentTimeMillis()}" }
        val safeStem = stem.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").take(48)
            .ifBlank { "font-${System.currentTimeMillis()}" }
        val extension = name.substringAfterLast('.', "").lowercase()
            .takeIf { it in FILE_TYPES } ?: "ttf"
        val target = uniqueFile(importDir(context), safeStem, extension)
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } != null && target.length() in 1..MAX_IMPORT_BYTES
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            return null
        }
        val usable = runCatching {
            val bytes = target.readBytes()
            FontCmap.hasUnicodeMap(bytes) && FontCmap.unicodeCoverage(bytes, "Aa1بم") > 0.2f
        }.getOrDefault(false)
        val typeface = if (usable) runCatching { Typeface.createFromFile(target) }.getOrNull() else null
        if (typeface == null) {
            target.delete()
            return null
        }
        return optionFor(target)
    }

    fun isImportedId(id: String): Boolean = id.startsWith(IMPORT_PREFIX)

    fun deleteImported(context: Context, id: String): Boolean {
        if (!isImportedId(id)) return false
        val name = id.removePrefix(IMPORT_PREFIX)
        val file = File(importDir(context), name)
        if (file.parentFile != importDir(context)) return false
        return file.delete()
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun uniqueFile(dir: File, stem: String, extension: String): File {
        var candidate = File(dir, "$stem.$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$index.$extension")
            index++
        }
        return candidate
    }

    private val FILE_TYPES = setOf("ttf", "otf", "ttc")
}
