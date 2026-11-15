package ir.lexilens.reader.pdf

import android.icu.text.Bidi

/**
 * Persian/Arabic PDFs are often stored in *visual* order: the glyphs appear in
 * the content stream from left to right, so the extracted string is the mirror
 * of the logical sentence. Reflowing that string as-is makes every word come
 * out backwards (and long-press then reports "another word").
 *
 * These helpers detect such lines and turn them back into logical order before
 * they are handed to [android.text.StaticLayout].
 */
internal object RtlText {

    fun hasStrongRtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return true
            }
        }
        return false
    }

    /** Share of strong characters that are right-to-left (0…1). */
    fun rtlShare(text: String): Float {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        val total = rtl + ltr
        return if (total == 0) 0f else rtl.toFloat() / total
    }

    /**
     * Converts a visually ordered RTL line into logical order, keeping Latin
     * words and digits intact (the inverse of how the PDF laid them out).
     */
    fun visualToLogical(visual: String): String {
        if (visual.isEmpty()) return visual
        return runCatching {
            val bidi = Bidi(visual, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
            bidi.setReorderingMode(Bidi.REORDER_INVERSE_LIKE_DIRECT.toInt())
            bidi.writeReordered(Bidi.DO_MIRRORING.toInt())
        }.getOrElse { visual.reversed() }
    }
}
