package lt.a777.muzika.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import lt.a777.muzika.data.Prefs

/** Muzika's own palette, used when wallpaper colours are off or unavailable. */
private val Brand = Color(0xFF2E6F40)
private val BrandLight = Color(0xFF7FD79A)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F0C4),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF50634F),
    tertiary = Color(0xFF3A656F),
    background = Color(0xFFF6FBF3),
    surface = Color(0xFFF6FBF3),
    surfaceVariant = Color(0xFFDCE5DB),
)

private val DarkScheme = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF15522D),
    onPrimaryContainer = Color(0xFFB4F0C4),
    secondary = Color(0xFFB7CCB4),
    tertiary = Color(0xFFA2CDD8),
    background = Color(0xFF0F140F),
    surface = Color(0xFF0F140F),
    surfaceVariant = Color(0xFF414941),
)

@Composable
fun MuzikaTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        Prefs.dynamicColour && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * A stable colour for a name, so the same mood keeps the same tile every time.
 */
fun tintFor(text: String): Color {
    var hash = 0
    for (character in text) hash = character.code + ((hash shl 5) - hash)
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), 0.45f, 0.55f)
}
