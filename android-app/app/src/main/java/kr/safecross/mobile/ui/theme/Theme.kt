package kr.safecross.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = HighContrastYellow,
    onPrimary = HighContrastBlack,
    secondary = ActionBlue,
    onSecondary = HighContrastWhite,
    background = HighContrastBlack,
    onBackground = HighContrastWhite,
    surface = CardBackground,
    onSurface = HighContrastWhite,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary
)

@Composable
fun SafeCrossTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HighContrastDarkColorScheme,
        content = content
    )
}
