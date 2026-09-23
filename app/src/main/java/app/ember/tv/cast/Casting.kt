package app.ember.tv.cast

import android.content.Context
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Tells the Cast SDK which receiver to launch. Google's Default Media
 * Receiver needs no registration and plays HLS and MP4, which covers most
 * IPTV live channels (via their HLS form) and most films.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Casting {

    /**
     * The shared CastContext, or null where casting cannot work: devices
     * without Google Play services (Fire TV, many Chinese-market phones) would
     * otherwise crash on the first Cast call.
     */
    fun context(context: Context): CastContext? = runCatching {
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) return null
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    }.getOrNull()

    private val XTREAM_LIVE_TS = Regex("""(/live/[^/]+/[^/]+/\d+)\.ts(\?.*)?$""")

    /**
     * The URL to hand the receiver. A Chromecast cannot play a raw MPEG-TS
     * stream, but Xtream panels serve the same channel as HLS if asked, so
     * live `.ts` URLs are rewritten to `.m3u8`.
     */
    fun castUrl(url: String): String =
        XTREAM_LIVE_TS.find(url)?.let { m -> url.replaceRange(m.range, m.groupValues[1] + ".m3u8" + m.groupValues[2]) }
            ?: url

    /** The receiver insists on a MIME type; guess one from the URL. */
    fun mimeFor(url: String, live: Boolean): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            // Extensionless live URLs are most often HLS behind a redirect.
            live -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.VIDEO_MP4
        }
    }
}
