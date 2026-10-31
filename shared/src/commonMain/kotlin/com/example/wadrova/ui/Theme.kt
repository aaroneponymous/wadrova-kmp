package com.example.wadrova.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import wadrova.shared.generated.resources.Res
import wadrova.shared.generated.resources.instrument_serif_italic
import wadrova.shared.generated.resources.manrope_bold
import wadrova.shared.generated.resources.manrope_extrabold
import wadrova.shared.generated.resources.manrope_medium

/** Design tokens carried over from the prototype. */
object WadrovaColors {
    val Ivory = Color(0xFFFAFAF8)
    val Warm = Color(0xFFFFFFFF)
    val Sand = Color(0xFFF0F0ED)
    val Studio = Color(0xFFECEDEA)
    val Line = Color(0xFFE6E7E4)
    val Charcoal = Color(0xFF232529)
    val Ink2 = Color(0xFF62666C)
    val Cobalt = Color(0xFF5966E9)
    val CobaltSoft = Color(0xFFEEF0FD)
}

@Composable
fun WadrovaTheme(content: @Composable () -> Unit) {
    val manrope = FontFamily(
        Font(Res.font.manrope_medium, FontWeight.Medium),
        Font(Res.font.manrope_bold, FontWeight.Bold),
        Font(Res.font.manrope_extrabold, FontWeight.ExtraBold),
    )
    val editorial = FontFamily(Font(Res.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic))

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = WadrovaColors.Cobalt,
            onPrimary = WadrovaColors.Warm,
            background = WadrovaColors.Ivory,
            onBackground = WadrovaColors.Charcoal,
            surface = WadrovaColors.Warm,
            onSurface = WadrovaColors.Charcoal,
            onSurfaceVariant = WadrovaColors.Ink2,
            outline = WadrovaColors.Line,
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = manrope, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.04).em),
            headlineSmall = TextStyle(fontFamily = editorial, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic, fontSize = 30.sp, lineHeight = 34.sp),
            titleLarge = TextStyle(fontFamily = manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
            titleSmall = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
            bodyMedium = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
            labelSmall = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.22.em),
        ),
        content = content,
    )
}
