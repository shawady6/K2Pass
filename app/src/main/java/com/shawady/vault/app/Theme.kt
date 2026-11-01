package com.shawady.vault.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** K2Pass identity: midnight navy, electric blue and orbit violet. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF38CFFF),
    onPrimary = Color(0xFF001923),
    primaryContainer = Color(0xFF123B68),
    onPrimaryContainer = Color(0xFFD9F5FF),
    secondary = Color(0xFFB56BFF),
    onSecondary = Color(0xFF250044),
    secondaryContainer = Color(0xFF482078),
    onSecondaryContainer = Color(0xFFF1DDFF),
    tertiary = Color(0xFF8B8DFF),
    background = Color(0xFF08091D),
    onBackground = Color(0xFFF2F2FF),
    surface = Color(0xFF11132B),
    onSurface = Color(0xFFF2F2FF),
    surfaceVariant = Color(0xFF202344),
    onSurfaceVariant = Color(0xFFB8BAD8),
    outline = Color(0xFF45496F),
    error = Color(0xFFFF6B83),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF087EA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F4FF),
    onPrimaryContainer = Color(0xFF003344),
    secondary = Color(0xFF7836B5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DFFF),
    onSecondaryContainer = Color(0xFF31005B),
    tertiary = Color(0xFF4C52B8),
    background = Color(0xFFF7F7FF),
    onBackground = Color(0xFF11132B),
    surface = Color.White,
    onSurface = Color(0xFF11132B),
    surfaceVariant = Color(0xFFECEBFA),
    onSurfaceVariant = Color(0xFF555674),
    outline = Color(0xFFC4C4DD),
    error = Color(0xFFB42345),
)

private val baseTypography = Typography()
private val K2Typography = baseTypography.copy(
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val K2Shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = K2Typography,
        shapes = K2Shapes,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

@Composable
fun LogoMark(size: Dp = 64.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(Color(0xFF08091D)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "K2Pass",
            modifier = Modifier.requiredSize(size * 1.18f),
        )
    }
}

private val avatarColors = listOf(
    0xFF087EA8, 0xFF6366F1, 0xFF9B51E0, 0xFFB56BFF, 0xFF0E9F9A, 0xFF2563EB, 0xFF7C3AED, 0xFFDB4777,
)

@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val c = Color(avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size])
    Box(Modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** A titled card used to group related controls. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
