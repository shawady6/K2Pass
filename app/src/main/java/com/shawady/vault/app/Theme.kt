package com.shawady.vault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * K2Pass visual system ("glass dark"): deep midnight gradient background, translucent bordered cards,
 * violet -> cyan brand gradient, rounded shapes, IBM Plex Sans Arabic, one vector icon set.
 * Dark only, by design (owner's choice, 2026-11-01).
 */
val BrandViolet = Color(0xFFA99BFF)
val BrandCyan = Color(0xFF4FD1FF)
val BrandBrush = Brush.linearGradient(listOf(BrandViolet, BrandCyan))
private val Ink = Color(0xFF0E1330)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Ink,
    primaryContainer = Color(0xFF332A73),
    onPrimaryContainer = Color(0xFFE9E5FF),
    secondary = Color(0xFF55CFFF),
    onSecondary = Color(0xFF062536),
    secondaryContainer = Color(0xFF223463),
    onSecondaryContainer = Color(0xFFDDE7FF),
    background = Color(0xFF0A0E1F),
    onBackground = Color(0xFFF1F2FA),
    surface = Color(0xFF12182E),
    onSurface = Color(0xFFF1F2FA),
    surfaceVariant = Color(0xFF1E2744),
    onSurfaceVariant = Color(0xFFB0B9D0),
    surfaceContainerLowest = Color(0xFF0A0E1F),
    surfaceContainerLow = Color(0xFF10162B),
    surfaceContainer = Color(0xFF141B34),
    surfaceContainerHigh = Color(0xFF1A2240),
    surfaceContainerHighest = Color(0xFF222B4D),
    outline = Color(0xFF3B4564),
    outlineVariant = Color(0xFF2A3350),
    error = Color(0xFFFF727F),
)

private val Plex = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

private fun TextStyle.plex(w: FontWeight? = null) = copy(fontFamily = Plex, fontWeight = w ?: fontWeight)

private val base = Typography()
private val K2Typography = Typography(
    displayLarge = base.displayLarge.plex(), displayMedium = base.displayMedium.plex(), displaySmall = base.displaySmall.plex(),
    headlineLarge = base.headlineLarge.plex(FontWeight.Bold), headlineMedium = base.headlineMedium.plex(FontWeight.Bold),
    headlineSmall = base.headlineSmall.plex(FontWeight.Bold),
    titleLarge = base.titleLarge.plex(FontWeight.Bold), titleMedium = base.titleMedium.plex(FontWeight.Medium),
    titleSmall = base.titleSmall.plex(FontWeight.Medium),
    bodyLarge = base.bodyLarge.plex(), bodyMedium = base.bodyMedium.plex(), bodySmall = base.bodySmall.plex(),
    labelLarge = base.labelLarge.plex(FontWeight.Medium), labelMedium = base.labelMedium.plex(FontWeight.Medium),
    labelSmall = base.labelSmall.plex(FontWeight.Medium),
)

// extraSmall is what Material uses for text fields: leaving it at the 4dp default made them look square.
private val K2Shapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = K2Typography, shapes = K2Shapes) {
        // The UI is Arabic, so the layout is always right-to-left regardless of the device language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF10163A), Color(0xFF0A0E1F), Color(0xFF080B18))))
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x334F46E5), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(900f, 0f), radius = 900f,
                        ),
                    ),
            ) { content() }
        }
    }
}

/** The K2Pass padlock logo on its dark tile (same artwork as the launcher icon). */
@Composable
fun LogoMark(size: Dp = 64.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2145), Color(0xFF0B1024)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.12f),
        )
    }
}

/** Rounded tile with a tinted icon (item types, list leading icons). */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

private val avatarColors = listOf(
    0xFFA99BFF, 0xFF6FA8FF, 0xFF55CFFF, 0xFF7BE0C8, 0xFFC39BFF, 0xFF8FA6FF, 0xFF6FD3E8, 0xFF9C8CF0,
)

/** Letter tile for logins (colour derived from the name). */
@Composable
fun Avatar(name: String, size: Dp = 46.dp) {
    val c = Color(avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size])
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(c.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = c, fontWeight = FontWeight.Bold)
    }
}

/** Translucent card with a soft gradient border: the building block of every screen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))
    val border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

/** A titled glass card used to group related controls. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
