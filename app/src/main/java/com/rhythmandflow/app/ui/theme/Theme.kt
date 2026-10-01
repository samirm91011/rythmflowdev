package com.rhythmandflow.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Rhythm & Flow brand palette (Brand Blueprint, "Cool Contemporary").
 * NOTE: the blueprint prints hex codes that do not match its own RGB values; the RGB values (and the rendered
 * swatches) are used here: Teal 78-151-139, Tangerine 227-99-61, Light Grey 218-218-218.
 */
object Brand {
    val Teal = Color(0xFF4E978B)
    /** Darker teal for filled buttons/links so white text meets contrast guidelines. */
    val TealDeep = Color(0xFF2F7A6E)
    val TealSoft = Color(0xFFE3F0ED)
    val Tangerine = Color(0xFFE3633D)
    val TangerineSoft = Color(0xFFFCE9E2)
    val LightGrey = Color(0xFFDADADA)
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF1B1B1B)
    val Muted = Color(0xFF666666)
    val Surface = Color(0xFFF6F6F4)
    val White = Color(0xFFFFFFFF)
    val Error = Color(0xFFB3261E)
}

private val RhythmColors = lightColorScheme(
    primary = Brand.TealDeep,
    onPrimary = Brand.White,
    primaryContainer = Brand.TealSoft,
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.Tangerine,
    onSecondary = Brand.White,
    secondaryContainer = Brand.TangerineSoft,
    onSecondaryContainer = Brand.Ink,
    background = Brand.White,
    onBackground = Brand.Ink,
    surface = Brand.White,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.Surface,
    onSurfaceVariant = Brand.Muted,
    outline = Brand.LightGrey,
    error = Brand.Error,
)

// Amaris (the brand's primary typeface) is a licensed font. Until its files are supplied, a serif stands in for headings;
// swap `HeadingFont` for FontFamily(Font(R.font.amaris_regular)) once the font files are in res/font.
val HeadingFont: FontFamily = FontFamily.Serif
val BodyFont: FontFamily = FontFamily.SansSerif

private val RhythmTypography = Typography(
    displayLarge = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

private val RhythmShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun RhythmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RhythmColors, typography = RhythmTypography, shapes = RhythmShapes, content = content)
}
