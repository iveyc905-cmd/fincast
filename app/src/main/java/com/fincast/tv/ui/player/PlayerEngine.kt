package com.fincast.tv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.fincast.tv.data.net.DEFAULT_USER_AGENT
import com.fincast.tv.data.net.Http

data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean,
    /** C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT. */
    val type: Int,
)

/**
 * Wraps ExoPlayer with the settings live IPTV actually needs.
 *
 * The defaults are tuned for streams that never end and often stutter: a large
 * enough buffer to ride out a hiccup, but a short start-up buffer so zapping
 * between channels still feels instant.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context) {

    private var trackSelector: DefaultTrackSelector? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    var player: ExoPlayer? = null
        private set

    fun create(userAgent: String?, bufferSeconds: Int, tunneling: Boolean): ExoPlayer {
        release()

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                // Tunneled playback keeps 4K HEVC smooth on weak TV SoCs, but a
                // few devices render a black screen with it, so it is a setting.
                .setTunnelingEnabled(tunneling)
                .setPreferredAudioLanguage(java.util.Locale.getDefault().isO3Language)
                .build()
        }
        trackSelector = selector

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ (bufferSeconds * 1000).coerceAtLeast(5_000),
                /* maxBufferMs = */ (bufferSeconds * 2000).coerceAtLeast(20_000),
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(context)
            // Falling back to software decoding beats a hard failure on a channel
            // the hardware decoder cannot handle.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(userAgent)))
            .setSeekForwardIncrementMs(30_000)
            .setSeekBackIncrementMs(15_000)
            .build()
            .also {
                it.playWhenReady = true
                player = it
            }
    }

    private fun dataSourceFactory(userAgent: String?): DataSource.Factory {
        val http = OkHttpDataSource.Factory(Http.client(context))
            .setUserAgent(userAgent ?: DEFAULT_USER_AGENT)
        httpFactory = http
        // DefaultDataSource wraps the HTTP source so local file:// playlists work too.
        return DefaultDataSource.Factory(context, http)
    }

    /**
     * @param referrer some providers gate streams on a Referer header; it has to
     *   go on the data source rather than the media item, and it can change per
     *   channel, so it is applied just before prepare.
     */
    fun play(url: String, referrer: String?) {
        httpFactory?.setDefaultRequestProperties(
            if (referrer.isNullOrBlank()) emptyMap() else mapOf("Referer" to referrer)
        )

        val builder = MediaItem.Builder().setUri(url)
        // Skip content sniffing when the extension is unambiguous — one less
        // round trip before the first frame.
        when {
            url.contains(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        player?.apply {
            setMediaItem(builder.build())
            prepare()
            playWhenReady = true
        }
    }

    fun audioTracks(): List<TrackOption> = tracksOfType(C.TRACK_TYPE_AUDIO)
    fun subtitleTracks(): List<TrackOption> = tracksOfType(C.TRACK_TYPE_TEXT)

    private fun tracksOfType(type: Int): List<TrackOption> {
        val tracks: Tracks = player?.currentTracks ?: return emptyList()
        val options = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = buildString {
                    append(format.label ?: format.language ?: "Track ${i + 1}")
                    format.codecs?.let { append(" · ").append(it.substringBefore('.')) }
                    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
                        append(" · ").append(format.channelCount).append("ch")
                    }
                }
                options += TrackOption(groupIndex, i, label, group.isTrackSelected(i), type)
            }
        }
        return options
    }

    fun selectTrack(option: TrackOption) {
        val tracks = player?.currentTracks ?: return
        val group = tracks.groups.getOrNull(option.groupIndex) ?: return
        trackSelector?.let { selector ->
            selector.parameters = selector.buildUponParameters()
                // Re-enable the type in case subtitles were switched off earlier.
                .setTrackTypeDisabled(option.type, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                )
                .build()
        }
    }

    fun disableSubtitles() {
        trackSelector?.let { selector ->
            selector.parameters = selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    fun release() {
        player?.release()
        player = null
        trackSelector = null
        httpFactory = null
    }
}
