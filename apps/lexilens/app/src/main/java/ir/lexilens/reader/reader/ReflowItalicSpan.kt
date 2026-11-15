package ir.lexilens.reader.reader

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * Draws a run in italic. When the font family has a real italic cut (or the PDF
 * embeds one) that face is used; otherwise the base glyphs are slanted, because
 * a single-file custom font cannot synthesise italic on its own.
 */
internal class ReflowItalicSpan(private val face: Typeface?) : MetricAffectingSpan() {

    override fun updateDrawState(tp: TextPaint) = apply(tp)

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(tp: TextPaint) {
        if (face != null) {
            tp.typeface = face
        } else {
            tp.textSkewX = SKEW
        }
    }

    companion object {
        private const val SKEW = -0.22f
    }
}
