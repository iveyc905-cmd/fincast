package com.fincast.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A living-room palette: near-black surfaces so the panel does not glare over
 * video, and a single saturated accent that reads clearly from three metres away.
 */
private val FincastColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00121F),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFE9EEF5),
    surface = Color(0xFF10141B),
    onSurface = Color(0xFFE9EEF5),
    surfaceVariant = Color(0xFF1B2230),
    onSurfaceVariant = Color(0xFFA9B4C4),
    outline = Color(0xFF33405A),
    error = Color(0xFFFF6B6B),
)

/** Panel backgrounds sit over live video, so they are translucent by design. */
object Scrim {
    val panel = Color(0xE60B0F16)
    val panelDeep = Color(0xF20A0D13)
    val row = Color(0x1AFFFFFF)
    val rowSelected = Color(0xFF1E4E82)
    val bar = Color(0xCC05070A)
}

/** Ten-foot sizes: read from a sofa across the room. */
private val TvTypography = Typography(
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp),
)

/** Hand-held sizes: a phone is a foot away, and screen height is scarce. */
private val TouchTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp),
)

@Composable
fun FincastTheme(tv: Boolean, content: @Composable () -> Unit) {
    // The app is dark-only on purpose; a light UI over live video is unusable.
    MaterialTheme(
        colorScheme = FincastColors,
        typography = if (tv) TvTypography else TouchTypography,
        content = content,
    )
}
