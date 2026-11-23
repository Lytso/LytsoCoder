package lytso.code.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Prismatic Neon Rainbow brushes and strokes used by the special Rainbow AMOLED theme. */
object RainbowAccent {
    val spectrumColors: List<Color> = listOf(
        Color(0xFFFF2A85),
        Color(0xFFFF7A00),
        Color(0xFFFFE600),
        Color(0xFF00F5A0),
        Color(0xFF00D9FF),
        Color(0xFF9B5CFF),
        Color(0xFFFF2A85)
    )

    fun horizontalBrush(alpha: Float = 1f): Brush =
        Brush.horizontalGradient(spectrumColors.map { it.copy(alpha = alpha) })

    fun diagonalBrush(alpha: Float = 1f): Brush =
        Brush.linearGradient(spectrumColors.map { it.copy(alpha = alpha) })

    fun borderStroke(width: Dp = 1.25.dp, alpha: Float = 0.78f): BorderStroke =
        BorderStroke(width, horizontalBrush(alpha))
}
