package app.ember.tv.ui.mobile

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Gives every channel a brand colour taken from its logo, so tiles and guide
 * rows are recognisable at a glance the way a printed TV listing is.
 *
 * Colours are pulled toward a mid-dark band so white text stays readable on
 * them whatever the logo looked like; channels without a logo get a stable
 * hue derived from their name.
 */
object ChannelColors {
    private val cache = ConcurrentHashMap<String, Color>()

    fun fallback(name: String): Color {
        val hue = (abs(name.lowercase().hashCode()) % 360).toFloat()
        return Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.36f)))
    }

    suspend fun fromLogo(context: Context, url: String, name: String): Color {
        cache[url]?.let { return it }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false) // Palette needs to read pixels
                    .size(96)
                    .build()
                val drawable = context.imageLoader.execute(request).drawable as? BitmapDrawable
                val bitmap = drawable?.bitmap ?: return@runCatching null
                val palette = Palette.from(bitmap).clearFilters().generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                swatch?.rgb?.let(::tame)
            }.getOrNull()
        } ?: fallback(name)
        cache[url] = color
        return color
    }

    /** Clamps lightness and floors saturation so the colour works as a backdrop. */
    private fun tame(rgb: Int): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        // Near-greys (white-on-transparent logos are common) get no saturation
        // boost: a grey tile is more honest than an invented hue.
        if (hsl[1] > 0.12f) hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.26f, 0.42f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}

@Composable
fun rememberChannelColor(logo: String?, name: String): Color {
    val context = LocalContext.current
    var color by remember(logo, name) { mutableStateOf(ChannelColors.fallback(name)) }
    if (!logo.isNullOrBlank()) {
        LaunchedEffect(logo) { color = ChannelColors.fromLogo(context, logo, name) }
    }
    return color
}
