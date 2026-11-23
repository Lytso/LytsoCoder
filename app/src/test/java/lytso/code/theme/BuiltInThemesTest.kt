package lytso.code.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInThemesTest {
    private fun assertWcagAa(theme: AppTheme) {
        val resolved = ThemeManager.resolvedColors(theme)
        val issues = ThemeTokens.check(resolved)
        assertTrue("${theme.name} contrast issues: $issues", issues.isEmpty())

        val criticalPairs = listOf(
            "on_primary" to "primary",
            "on_primary_container" to "primary_container",
            "on_secondary" to "secondary",
            "on_tertiary" to "tertiary",
            "on_error" to "error",
            "on_background" to "background",
            "on_surface" to "surface",
            "on_surface" to "card",
            "on_surface_variant" to "surface"
        )
        for ((fg, bg) in criticalPairs) {
            val f = Color(resolved.getValue(fg).toInt())
            val b = Color(resolved.getValue(bg).toInt())
            val ratio = ThemeTokens.contrast(f, b)
            assertTrue("${theme.name}: $fg on $bg has contrast ${"%.2f".format(ratio)} (< 4.5)", ratio >= 4.5f)
        }
    }

    @Test
    fun rainbowAmoledHasTrueBlackSurfacesAndValidContrast() {
        val theme = BuiltInThemes.all.firstOrNull { it.id == BuiltInThemes.RAINBOW_AMOLED }
        requireNotNull(theme) { "BuiltInThemes.RAINBOW_AMOLED missing from BuiltInThemes.all" }
        assertTrue(theme.dark)
        assertEquals(0xFF000000L, theme.colors["background"])
        assertEquals(0xFF000000L, theme.colors["surface"])
        assertEquals(0xFF000000L, theme.colors["editor_bg"])
        assertEquals(0xFF000000L, theme.colors["terminal_bg"])

        assertWcagAa(theme)
    }

    @Test
    fun neonLightHasCrispWhiteSurfacesAndWcagAaContrast() {
        val theme = BuiltInThemes.all.firstOrNull { it.id == BuiltInThemes.NEON_LIGHT }
        requireNotNull(theme) { "BuiltInThemes.NEON_LIGHT missing from BuiltInThemes.all" }
        assertFalse(theme.dark)
        assertEquals(0xFFFFFFFFL, theme.colors["surface"])
        assertEquals(0xFFFFFFFFL, theme.colors["card"])

        assertWcagAa(theme)
    }
}
