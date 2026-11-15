package ro.camara.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF315D4D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0EACF), onPrimaryContainer = Color(0xFF203F31),
    secondary = Color(0xFF9A573D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E2D6), onSecondaryContainer = Color(0xFF61351F),
    tertiary = Color(0xFF806625), tertiaryContainer = Color(0xFFF2E7C5), onTertiaryContainer = Color(0xFF514010),
    background = Color(0xFFF7F7F0), onBackground = Color(0xFF202D26), surface = Color(0xFFF7F7F0),
    surfaceVariant = Color(0xFFE9ECE1), onSurface = Color(0xFF202D26),
    onSurfaceVariant = Color(0xFF647166), outline = Color(0xFF89988B), outlineVariant = Color(0xFFD9E0D3),
    surfaceContainerLowest = Color(0xFFFFFEF9), surfaceContainerLow = Color(0xFFF0F2E9),
    surfaceContainer = Color(0xFFEBEEE3), surfaceContainerHigh = Color(0xFFE4E9DC), surfaceContainerHighest = Color(0xFFDEE4D6),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB6D5AB), onPrimary = Color(0xFF1C3C2B),
    primaryContainer = Color(0xFF2D4737), onPrimaryContainer = Color(0xFFDBEBCD),
    secondary = Color(0xFFEDB394), onSecondary = Color(0xFF4B291A),
    secondaryContainer = Color(0xFF49362C), onSecondaryContainer = Color(0xFFFFD8BF),
    tertiary = Color(0xFFDDC585), tertiaryContainer = Color(0xFF423A24), onTertiaryContainer = Color(0xFFF6E5AF),
    background = Color(0xFF111C17), onBackground = Color(0xFFE7EDE2), surface = Color(0xFF111C17),
    surfaceVariant = Color(0xFF344136), onSurface = Color(0xFFE7EDE2), onSurfaceVariant = Color(0xFFB0BDAE),
    outline = Color(0xFF7C8D7E), outlineVariant = Color(0xFF35463A),
    surfaceContainerLowest = Color(0xFF16231C), surfaceContainerLow = Color(0xFF1B2A21),
    surfaceContainer = Color(0xFF223228), surfaceContainerHigh = Color(0xFF293B2F), surfaceContainerHighest = Color(0xFF314436),
)

private fun tintedScheme(dark: Boolean, background: Long, text: Long, muted: Long, primary: Long, secondary: Long, tertiary: Long): ColorScheme {
    val surface = Color(background)
    val ink = Color(text)
    val mutedInk = Color(muted)
    val main = Color(primary)
    val accent = Color(secondary)
    val extra = Color(tertiary)
    val onAccent = if (dark) surface else Color.White
    fun container(color: Color) = lerp(surface, color, if (dark) .27f else .16f)
    fun onContainer(color: Color) = lerp(color, ink, if (dark) .40f else .55f)
    fun layer(amount: Float) = lerp(surface, ink, amount)

    return (if (dark) Dark else Light).copy(
        primary = main, onPrimary = onAccent,
        primaryContainer = container(main), onPrimaryContainer = onContainer(main),
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = container(accent), onSecondaryContainer = onContainer(accent),
        tertiary = extra, onTertiary = onAccent,
        tertiaryContainer = container(extra), onTertiaryContainer = onContainer(extra),
        background = surface, onBackground = ink,
        surface = surface, onSurface = ink, surfaceTint = main,
        surfaceVariant = layer(.09f), onSurfaceVariant = mutedInk,
        outline = lerp(mutedInk, surface, .18f), outlineVariant = layer(.15f),
        surfaceContainerLowest = if (dark) layer(.025f) else lerp(surface, Color.White, .8f),
        surfaceContainerLow = layer(.035f), surfaceContainer = layer(.055f),
        surfaceContainerHigh = layer(.08f), surfaceContainerHighest = layer(.105f),
        surfaceBright = layer(if (dark) .11f else 0f), surfaceDim = layer(.09f),
        inverseSurface = ink, inverseOnSurface = surface, inversePrimary = if (dark) Color(0xFF3A5266) else Color(0xFFBED5EE),
    )
}

private val Palettes = mapOf(
    CamaraPalette.Sage to (Light to Dark),
    CamaraPalette.Ocean to (
        tintedScheme(false, 0xFFF1F7FA, 0xFF162E3A, 0xFF476572, 0xFF1E6078, 0xFF356D62, 0xFF685584) to
        tintedScheme(true, 0xFF0E1B24, 0xFFECF5F8, 0xFFABBFCA, 0xFF91D5EE, 0xFF9BD8C5, 0xFFCCB9F1)
    ),
    CamaraPalette.Lavender to (
        tintedScheme(false, 0xFFF7F3FC, 0xFF2D2240, 0xFF645774, 0xFF684798, 0xFF875175, 0xFF376B74) to
        tintedScheme(true, 0xFF1B1426, 0xFFF3EBFA, 0xFFCBBCD7, 0xFFD5BBFF, 0xFFF0B4D6, 0xFF9DD7DB)
    ),
    CamaraPalette.Terracotta to (
        tintedScheme(false, 0xFFFBF4EC, 0xFF38291F, 0xFF725C50, 0xFF97472D, 0xFF685923, 0xFF586735) to
        tintedScheme(true, 0xFF241810, 0xFFFAEEE4, 0xFFDBC2B2, 0xFFFFB69B, 0xFFE8D08C, 0xFFCBDC9D)
    ),
    CamaraPalette.Ink to (
        tintedScheme(false, 0xFFF3F4F6, 0xFF1D252C, 0xFF545F69, 0xFF374A5A, 0xFF5E556E, 0xFF536440) to
        tintedScheme(true, 0xFF15181B, 0xFFEEF1F3, 0xFFB9C0C8, 0xFFC5D5E4, 0xFFDAC6E5, 0xFFC8D8A9)
    ),
)

internal fun paletteScheme(palette: CamaraPalette, dark: Boolean): ColorScheme {
    val (light, night) = Palettes.getValue(palette)
    return if (dark) night else light
}

internal val LocalCamaraMotion = staticCompositionLocalOf { true }

private val CamaraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.52).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)
private val CamaraShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))

@Composable
fun CamaraTheme(darkTheme: Boolean = isSystemInDarkTheme(), palette: CamaraPalette = CamaraPalette.Sage,
    motion: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCamaraMotion provides motion) {
        MaterialTheme(colorScheme = paletteScheme(palette, darkTheme), typography = CamaraTypography, shapes = CamaraShapes, content = content)
    }
}
