package ir.lexilens.reader

import ir.lexilens.reader.reader.ReflowThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowThemesTest {

    @Test
    fun everyPresetHasReadableContrast() {
        assertTrue(ReflowThemes.presets.size >= 6)
        for (theme in ReflowThemes.presets) {
            val ratio = ReflowThemes.contrastRatio(theme.background, theme.text)
            assertTrue("${theme.name} contrast=$ratio", ratio >= 4.5)
        }
    }

    @Test
    fun detectsLightAndDarkSurfaces() {
        assertTrue(ReflowThemes.isLight(0xFFFFFFFF.toInt()))
        assertFalse(ReflowThemes.isLight(0xFF000000.toInt()))
        assertTrue(ReflowThemes.isLight(ReflowThemes.default.background))
        assertFalse(ReflowThemes.isLight(0xFF1F2937.toInt()))
    }

    @Test
    fun contrastRatioIsSymmetricAndBounded() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(21.0, ReflowThemes.contrastRatio(black, white), 0.01)
        assertEquals(
            ReflowThemes.contrastRatio(black, white),
            ReflowThemes.contrastRatio(white, black),
            0.0001,
        )
        assertEquals(1.0, ReflowThemes.contrastRatio(black, black), 0.0001)
    }

    @Test
    fun swatchesIncludeBothLightAndDarkChoices() {
        assertTrue(ReflowThemes.backgroundColors.any { ReflowThemes.isLight(it) })
        assertTrue(ReflowThemes.backgroundColors.any { !ReflowThemes.isLight(it) })
        assertTrue(ReflowThemes.textColors.any { ReflowThemes.isLight(it) })
        assertTrue(ReflowThemes.textColors.any { !ReflowThemes.isLight(it) })
    }
}
