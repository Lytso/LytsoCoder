package lytso.code.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInThemesTest {
    @Test
    fun rainbowAmoledHasTrueBlackSurfacesAndValidContrast() {
        val theme = BuiltInThemes.all.firstOrNull { it.id == BuiltInThemes.RAINBOW_AMOLED }
        requireNotNull(theme) { "BuiltInThemes.RAINBOW_AMOLED missing from BuiltInThemes.all" }
        assertTrue(theme.dark)
        assertEquals(0xFF000000L, theme.colors["background"])
        assertEquals(0xFF000000L, theme.colors["surface"])
        assertEquals(0xFF000000L, theme.colors["editor_bg"])
        assertEquals(0xFF000000L, theme.colors["terminal_bg"])

        val issues = ThemeTokens.check(ThemeManager.resolvedColors(theme))
        assertTrue("Rainbow AMOLED contrast issues: $issues", issues.isEmpty())
    }
}
