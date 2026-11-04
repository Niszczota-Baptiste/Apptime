package fr.montemps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Thème volontairement sobre, pour ne pas distraire :
 * - palette neutre « encre / papier », sans couleurs dynamiques (Material You) ni teintes d'élévation ;
 * - les couleurs des activités sont les seuls accents de l'interface ;
 * - clair ou sombre selon le réglage du système.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF151516),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E5),
    onPrimaryContainer = Color(0xFF151516),
    inversePrimary = Color(0xFFE8E8E5),
    secondary = Color(0xFF5E5E63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECECE9),
    onSecondaryContainer = Color(0xFF151516),
    tertiary = Color(0xFF5E5E63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECECE9),
    onTertiaryContainer = Color(0xFF151516),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF151516),
    surface = Color(0xFFF7F7F5),
    onSurface = Color(0xFF151516),
    surfaceVariant = Color(0xFFECECE9),
    onSurfaceVariant = Color(0xFF6A6A70),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF151516),
    inverseOnSurface = Color(0xFFF7F7F5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFC4C4C0),
    outlineVariant = Color(0xFFE4E4E0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9E9E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3F1),
    surfaceContainer = Color(0xFFEEEEEB),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4E4E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1F1EF),
    onPrimary = Color(0xFF151516),
    primaryContainer = Color(0xFF2B2B2E),
    onPrimaryContainer = Color(0xFFF1F1EF),
    inversePrimary = Color(0xFF151516),
    secondary = Color(0xFFB4B4B9),
    onSecondary = Color(0xFF151516),
    secondaryContainer = Color(0xFF252528),
    onSecondaryContainer = Color(0xFFF1F1EF),
    tertiary = Color(0xFFB4B4B9),
    onTertiary = Color(0xFF151516),
    tertiaryContainer = Color(0xFF252528),
    onTertiaryContainer = Color(0xFFF1F1EF),
    background = Color(0xFF0F0F10),
    onBackground = Color(0xFFF1F1EF),
    surface = Color(0xFF0F0F10),
    onSurface = Color(0xFFF1F1EF),
    surfaceVariant = Color(0xFF1E1E21),
    onSurfaceVariant = Color(0xFF9D9DA3),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF1F1EF),
    inverseOnSurface = Color(0xFF151516),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF48484D),
    outlineVariant = Color(0xFF2A2A2E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2E2E31),
    surfaceDim = Color(0xFF0F0F10),
    surfaceContainerLowest = Color(0xFF0A0A0B),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF1A1A1C),
    surfaceContainerHigh = Color(0xFF212124),
    surfaceContainerHighest = Color(0xFF2A2A2E),
)

private val Base = Typography()

/** Titres resserrés, grands chiffres en graisse fine, aucun espacement de lettres superflu. */
private val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.25).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = Base.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = Base.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = Base.labelSmall.copy(letterSpacing = 0.2.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MonTempsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
