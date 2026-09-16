package com.sahayat.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SAHAYAT design tokens. Every screen uses these — never ad-hoc colors. */
object SColors {
    // Glossy black base
    val BgTop = Color(0xFF000000)
    val BgMid = Color(0xFF060807)
    val BgBottom = Color(0xFF0B0D0C)
    val Accent = Color(0xFF2AC976)
    val AccentMuted = Color(0x332AC976)
    val Card = Color(0xFF0F1211)
    val CardAlt = Color(0xFF151917)
    val Stroke = Color(0x1FFFFFFF)
    /** Green-tinted tile behind row icons. */
    val IconTile = Color(0xFF0F2319)
    val IconTileStroke = Color(0x402AC976)
    /** Filled circle for the selected bottom-nav item and header buttons. */
    val Chip = Color(0xFF1A1E1C)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFB1C0CD)
    val TextTertiary = Color(0xFF7D8C97)

    /** Interaction state: cream surface with black content. */
    val Cream = Color(0xFFF4EFE3)
    val OnCream = Color(0xFF000000)

    /** Status colors — used sparingly, only for real status meaning. */
    val Danger = Color(0xFFE5484D)
    val DangerMuted = Color(0x33E5484D)
    val Warning = Color(0xFFE8B04A)
    val WarningMuted = Color(0x33E8B04A)
    val Neutral = Color(0xFF6B7A86)
    val NeutralMuted = Color(0x336B7A86)

    val background: Brush
        get() = Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom))

    /** Soft light sheen drawn over the black base for the glossy look. */
    val sheen: Brush
        get() = Brush.radialGradient(
            colors = listOf(Color(0x14FFFFFF), Color(0x05FFFFFF), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, 0f),
            radius = 1400f,
        )

    /** Subtle top-to-bottom highlight on cards. */
    val cardGloss: Brush
        get() = Brush.verticalGradient(listOf(Color(0x0FFFFFFF), Color(0x03FFFFFF), Color.Transparent))
}

object SShapes {
    val Card = RoundedCornerShape(26.dp)
    val CardSmall = RoundedCornerShape(24.dp)
    val Tile = RoundedCornerShape(14.dp)
    val Hero = RoundedCornerShape(28.dp)
    val Pill = CircleShape
    val Field = RoundedCornerShape(16.dp)
}

object SDimens {
    val ScreenPadding = 20.dp
    val CardPadding = 18.dp
    val IconButton = 46.dp
    val Gap = 12.dp
}

private val Sans = FontFamily.SansSerif

object SType {
    val Title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, color = SColors.TextPrimary, letterSpacing = (-0.5).sp)
    val Eyebrow = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp, color = SColors.Accent)
    val Section = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, color = SColors.TextPrimary)
    val TitleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, color = SColors.TextPrimary, letterSpacing = (-0.4).sp)
    val CardTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = SColors.TextPrimary)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = SColors.TextSecondary)
    val BodyStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = SColors.TextPrimary)
    val Caption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, color = SColors.TextSecondary)
    val Label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp, color = SColors.TextTertiary)
    val Metric = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, color = SColors.TextPrimary)
    val Button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
}

private val scheme = darkColorScheme(
    primary = SColors.Accent,
    onPrimary = Color(0xFF04140B),
    secondary = SColors.Accent,
    background = SColors.BgTop,
    onBackground = SColors.TextPrimary,
    surface = SColors.Card,
    onSurface = SColors.TextPrimary,
    surfaceVariant = SColors.CardAlt,
    onSurfaceVariant = SColors.TextSecondary,
    error = SColors.Danger,
    outline = SColors.Stroke,
)

@Composable
fun SahayatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineMedium = SType.Title,
            titleMedium = SType.CardTitle,
            bodyMedium = SType.Body,
            labelLarge = SType.Button,
        ),
        content = content,
    )
}
