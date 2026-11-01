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

/** K2Pass visual system: midnight navy, electric violet, royal blue and ice cyan. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9B8CFF),
    onPrimary = Color(0xFF17102F),
    primaryContainer = Color(0xFF34276F),
    onPrimaryContainer = Color(0xFFE9E5FF),
    secondary = Color(0xFF55CFFF),
    onSecondary = Color(0xFF062536),
    background = Color(0xFF090D1C),
    onBackground = Color(0xFFF1F2FA),
    surface = Color(0xFF12182B),
    onSurface = Color(0xFFF1F2FA),
    surfaceVariant = Color(0xFF202943),
    onSurfaceVariant = Color(0xFFB0B9D0),
    outline = Color(0xFF3B4564),
    error = Color(0xFFFF727F),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6553D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E4FF),
    onPrimaryContainer = Color(0xFF21165C),
    secondary = Color(0xFF087FA8),
    background = Color(0xFFF6F7FC),
    onBackground = Color(0xFF101426),
    surface = Color.White,
    onSurface = Color(0xFF101426),
    surfaceVariant = Color(0xFFEAECF5),
    onSurfaceVariant = Color(0xFF515B75),
    outline = Color(0xFFBEC4D8),
    error = Color(0xFFC62842),
)

private val baseTypography = Typography()
private val K2Typography = baseTypography.copy(
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

private val K2Shapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
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
            .background(Color(0xFF10152B)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.45f),
        )
    }
}

private val avatarColors = listOf(
    0xFF6553D9, 0xFF2878E8, 0xFF0B9FC2, 0xFF9A65D8, 0xFF257C9B, 0xFF5968C9, 0xFF8B55B8, 0xFF3D83B8,
)

@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val c = Color(avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size])
    Box(Modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
