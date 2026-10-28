package ai.scan3d.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.scan3d.app.data.ThemeMode

val Blue = Color(0xFF1E6BFF)
val BlueLight = Color(0xFF7FA8FF)
val Navy = Color(0xFF0B1B3A)
val Success = Color(0xFF1F9D63)
val Warning = Color(0xFFE08A00)
val Danger = Color(0xFFD93A3A)

private val Light = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF), onPrimaryContainer = Color(0xFF00184A),
    secondary = Color(0xFF4A5D80), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F5), onSecondaryContainer = Color(0xFF16233B),
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF2F9), onSurfaceVariant = Color(0xFF4B5567),
    outline = Color(0xFFCBD3E1), error = Danger,
)

private val Dark = darkColorScheme(
    primary = BlueLight, onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1B3E86), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB4C3E2), onSecondary = Color(0xFF1E2E4B),
    secondaryContainer = Color(0xFF26334C), onSecondaryContainer = Color(0xFFDCE4F5),
    background = Color(0xFF0E1420), onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF151C2B), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2738), onSurfaceVariant = Color(0xFFAAB4C6),
    outline = Color(0xFF39445A), error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun Scan3DTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
