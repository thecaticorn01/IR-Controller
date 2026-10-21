package com.thecaticorn01.ircontroller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.thecaticorn01.ircontroller.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF5149C9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF130068),
    secondary = Color(0xFF5E5C71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFF9),
    onSecondaryContainer = Color(0xFF1B192C),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF31101D),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF0ECF4),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE5E1E9),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5C0FF),
    onPrimary = Color(0xFF241A9F),
    primaryContainer = Color(0xFF3B30B2),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFFC8C3DC),
    onSecondary = Color(0xFF302E42),
    secondaryContainer = Color(0xFF474459),
    onSecondaryContainer = Color(0xFFE4DFF9),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF4A2532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF131218),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131218),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainerLowest = Color(0xFF0E0D13),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),
)

/** Colours with fixed meaning on a remote (power, colour keys, "on"). */
object RemoteColors {
    val power = Color(0xFFE5484D)
    val on = Color(0xFF2E9E5B)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF30A46C)
    val yellow = Color(0xFFF5C33B)
    val blue = Color(0xFF3E63DD)
}

@Composable
fun IRTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
