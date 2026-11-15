package ir.lexilens.reader.reader

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ReflowTheme(
    val name: String,
    val background: Int,
    val text: Int,
)

/**
 * Reading themes for reflow mode. Presets cover the common cases (paper,
 * sepia, night, high contrast); the swatch lists let the user build any
 * combination without a colour wheel.
 */
object ReflowThemes {

    val presets = listOf(
        ReflowTheme("کاغذ", 0xFFF6F4ED.toInt(), 0xFF14231E.toInt()),
        ReflowTheme("کرم", 0xFFFBF3E0.toInt(), 0xFF3B2A14.toInt()),
        ReflowTheme("سفید", 0xFFFFFFFF.toInt(), 0xFF111827.toInt()),
        ReflowTheme("شب", 0xFF0B1512.toInt(), 0xFFE7F0EC.toInt()),
        ReflowTheme("مشکی", 0xFF000000.toInt(), 0xFFF5F5F5.toInt()),
        ReflowTheme("خاکستری", 0xFF1F2937.toInt(), 0xFFE5E7EB.toInt()),
        ReflowTheme("سبز", 0xFF0F2A22.toInt(), 0xFFD8F3E8.toInt()),
        ReflowTheme("آبی", 0xFF0E1B2A.toInt(), 0xFFDCEBFF.toInt()),
    )

    val default: ReflowTheme get() = presets[0]

    val textColors = listOf(
        0xFF14231E.toInt(),
        0xFF000000.toInt(),
        0xFF374151.toInt(),
        0xFF4B5563.toInt(),
        0xFF3B2A14.toInt(),
        0xFF78350F.toInt(),
        0xFF0F766E.toInt(),
        0xFF1D4ED8.toInt(),
        0xFF7C3AED.toInt(),
        0xFFBE123C.toInt(),
        0xFFE7F0EC.toInt(),
        0xFFF8FAFC.toInt(),
        0xFFFDE68A.toInt(),
        0xFF5EEAD4.toInt(),
    )

    val backgroundColors = listOf(
        0xFFF6F4ED.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFFBF3E0.toInt(),
        0xFFFEF3C7.toInt(),
        0xFFE8F5E9.toInt(),
        0xFFE0F2FE.toInt(),
        0xFFFCE7F3.toInt(),
        0xFFF3F4F6.toInt(),
        0xFF0B1512.toInt(),
        0xFF000000.toInt(),
        0xFF1F2937.toInt(),
        0xFF0F2A22.toInt(),
        0xFF0E1B2A.toInt(),
        0xFF2A1620.toInt(),
    )

    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value.coerceIn(0, 255) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun isLight(color: Int): Boolean = luminance(color) > 0.55

    /** WCAG contrast ratio (1.0 … 21.0). */
    fun contrastRatio(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
