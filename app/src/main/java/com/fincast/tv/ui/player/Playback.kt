package com.fincast.tv.ui.player

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fincast.tv.Graph
import com.fincast.tv.data.repo.AspectMode

/** Observable handle on the player, shared by the TV and touch layouts. */
@Stable
class Playback(val engine: PlayerEngine) {
    /** False until the player has been built from stored preferences. */
    var ready by mutableStateOf(false)
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    /** Whether start-up is restoring the last-watched channel. */
    internal var resuming = false

    fun togglePlayPause() {
        val player = engine.player ?: return
        if (player.isPlaying) player.pause() else player.play()
    }
}

/**
 * Owns the player's lifetime and keeps it in step with the view model:
 * builds it once, tunes whenever the channel changes, and pauses while the app
 * is in the background.
 *
 * @param autoTuneFirst start the first channel when there is nothing to resume.
 *   Right for a TV, where a black screen with no visible controls reads as
 *   broken; wrong for a phone, where the channel list is right there.
 */
@Composable
fun rememberPlayback(viewModel: PlayerViewModel, autoTuneFirst: Boolean): Playback {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingUrl.collectAsStateWithLifecycle()
    val playback = remember { Playback(PlayerEngine(context.applicationContext)) }
    val engine = playback.engine

    LaunchedEffect(Unit) {
        val settings = Graph.settings.current()
        val player = engine.create(
            userAgent = settings.userAgent,
            bufferSeconds = settings.bufferSeconds,
            tunneling = settings.tunnelingEnabled,
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.setBuffering(playbackState == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playback.isPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.reportError(friendlyError(error))
            }
        })
        playback.resuming = settings.resumeOnStart && settings.lastChannelId != 0L
        playback.ready = true

        // Come back to the last channel, the way a TV does.
        if (playback.resuming) viewModel.tune(settings.lastChannelId)
    }

    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    // Live streams keep downloading while paused, so stop when the app is hidden
    // and rejoin at the live edge on return rather than hours behind.
    val lifecycleOwner = LocalLifecycleOwner.current
    val inCatchup by rememberUpdatedState(state.catchup != null)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val player = engine.player ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) {
                    if (!inCatchup) player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(playback.ready, state.currentChannel?.id) {
        if (!playback.ready) return@LaunchedEffect
        val channel = state.currentChannel ?: return@LaunchedEffect
        if (state.catchup == null) engine.play(channel.url, channel.referrer)
    }

    // Catch-up and return-to-live push an explicit URL instead.
    LaunchedEffect(playback.ready, pending) {
        if (!playback.ready) return@LaunchedEffect
        pending?.let { (url, referrer) ->
            engine.play(url, referrer)
            viewModel.consumedPendingUrl()
        }
    }

    if (autoTuneFirst) {
        LaunchedEffect(playback.ready, state.channels.isNotEmpty()) {
            if (playback.ready && !playback.resuming && state.currentChannel == null) {
                state.channels.firstOrNull()?.let { viewModel.tune(it.channel) }
            }
        }
    }

    // Transient messages fade on their own; nothing here needs acknowledging.
    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            kotlinx.coroutines.delay(6_000)
            viewModel.clearStatus()
        }
    }

    return playback
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(playback: Playback, aspectMode: AspectMode, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // Both layouts draw their own controls.
                useController = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(AndroidColor.BLACK)
            }
        },
        update = { view ->
            if (playback.ready) view.player = playback.engine.player
            view.resizeMode = when (aspectMode) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        // Rotating between layouts can recreate the view; detach the old one so
        // it cannot steal the video surface back.
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Cannot reach the stream — check the connection"

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Provider refused the stream (it may be at its connection limit)"

    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        "This device cannot decode the channel's video format"

    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "The stream is malformed or offline"

    else -> "Playback failed (${error.errorCodeName})"
}
