package app.ember.tv.ui.player

import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.ember.tv.Graph
import app.ember.tv.cast.Casting
import app.ember.tv.data.repo.AspectMode
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observable handle on playback, shared by the TV and touch layouts.
 *
 * Owns two players: the on-device ExoPlayer and, where Google Play services
 * exist, a CastPlayer. [activePlayer] is whichever is showing the picture, so
 * controls work the same whether the stream is on the phone or on the TV.
 */
@Stable
@OptIn(UnstableApi::class)
class Playback(val engine: PlayerEngine, private val castContext: CastContext?) {
    /** False until the player has been built from stored preferences. */
    var ready by mutableStateOf(false)
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    var casting by mutableStateOf(false)
        private set
    var castDevice by mutableStateOf<String?>(null)
        private set
    var current by mutableStateOf<PlayItem?>(null)
        private set
    /** Polled twice a second while something plays; drives seek bars. */
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set

    internal var castPlayer: CastPlayer? = null
    internal var retries = 0
    /** Whether start-up is restoring the last-watched channel. */
    internal var resuming = false

    /** Known at construction, so screens can show the Cast button from their first frame. */
    val castAvailable: Boolean get() = castContext != null

    val activePlayer: Player? get() = if (casting) castPlayer else engine.player

    fun load(item: PlayItem?) {
        current = item
        retries = 0
        positionMs = 0
        durationMs = 0
        when {
            item == null -> {
                engine.player?.run { stop(); clearMediaItems() }
                castPlayer?.run { stop(); clearMediaItems() }
                isPlaying = false
            }
            casting -> loadOnCast(item, item.startPositionMs)
            else -> engine.play(item.url, item.referrer, item.startPositionMs, item.isLive)
        }
    }

    fun togglePlayPause() {
        val player = activePlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val player = activePlayer ?: return
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = player.duration
        player.seekTo(if (duration != C.TIME_UNSET && duration > 0) target.coerceAtMost(duration - 1_000) else target)
        positionMs = player.currentPosition
    }

    fun seekTo(ms: Long) {
        activePlayer?.seekTo(ms.coerceAtLeast(0))
        positionMs = ms
    }

    private fun loadOnCast(item: PlayItem, startMs: Long) {
        val player = castPlayer ?: return
        val url = Casting.castUrl(item.url)
        val media = MediaItem.Builder()
            .setUri(url)
            .setMimeType(Casting.mimeFor(url, item.isLive))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .setArtworkUri(item.artwork?.let(Uri::parse))
                    .build()
            )
            .build()
        if (!item.isLive && startMs > 0) player.setMediaItem(media, startMs) else player.setMediaItem(media)
        player.prepare()
        player.playWhenReady = true
    }

    /** A Cast session started: move whatever is playing onto the TV. */
    internal fun switchToCast() {
        if (casting) return
        val position = engine.player?.currentPosition ?: 0
        engine.player?.run { pause(); stop() }
        casting = true
        castDevice = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        current?.let { loadOnCast(it, if (it.isLive) 0 else position) }
    }

    /** The Cast session ended: pick the stream back up on the phone. */
    internal fun switchToLocal() {
        if (!casting) return
        val position = castPlayer?.currentPosition ?: 0
        casting = false
        castDevice = null
        current?.let { engine.play(it.url, it.referrer, if (it.isLive) 0 else position, it.isLive) }
    }
}

/**
 * Owns the players' lifetime and keeps them in step with the view model:
 * builds them once, loads each play request, pauses local playback in the
 * background, and saves resume positions for films and episodes.
 *
 * @param resumeLast pick up the last channel on launch. A TV should; a phone
 *   should not start audio on its own with the picture hidden.
 * @param autoTuneFirst start the first channel when there is nothing to resume.
 *   Right for a TV, where a black screen with no visible controls reads as
 *   broken; wrong for a phone, where the channel list is right there.
 * @param enableCast offer Google Cast. Only the touch layout does; TVs are the
 *   thing being cast to.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayback(
    viewModel: PlayerViewModel,
    autoTuneFirst: Boolean,
    resumeLast: Boolean = true,
    enableCast: Boolean = false,
): Playback {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val request by viewModel.playRequest.collectAsStateWithLifecycle()
    val playback = remember {
        Playback(
            engine = PlayerEngine(context.applicationContext),
            castContext = if (enableCast) Casting.context(context) else null,
        )
    }
    val engine = playback.engine
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val settings = Graph.settings.current()
        val player = engine.create(
            userAgent = settings.userAgent,
            bufferSeconds = settings.bufferSeconds,
            tunneling = settings.tunnelingEnabled,
        )

        fun listenerFor(isCast: Boolean) = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isCast != playback.casting) return
                viewModel.setBuffering(playbackState == Player.STATE_BUFFERING)
                when (playbackState) {
                    Player.STATE_READY -> playback.retries = 0
                    Player.STATE_ENDED -> {
                        playback.current?.progress?.let { viewModel.finishedProgress(it) }
                        viewModel.onPlaybackEnded()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCast == playback.casting) playback.isPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isCast != playback.casting) return
                if (isCast) {
                    viewModel.reportError("The Cast device could not play this stream")
                    return
                }
                // Live streams hiccup; a couple of quiet retries beats showing an
                // error for a stall that would have cleared itself.
                if (playback.retries < 3 && isRecoverable(error)) {
                    playback.retries++
                    val attempt = playback.retries
                    scope.launch {
                        delay(1_500L * attempt)
                        engine.player?.apply { prepare(); play() }
                    }
                    return
                }
                viewModel.reportError(friendlyError(error))
            }
        }
        player.addListener(listenerFor(isCast = false))

        playback.castPlayer = runCatching {
            val castContext = if (enableCast) Casting.context(context) else null
            castContext?.let { CastPlayer(it) }
        }.getOrNull()?.also { cast ->
            cast.addListener(listenerFor(isCast = true))
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = playback.switchToCast()
                override fun onCastSessionUnavailable() = playback.switchToLocal()
            })
            if (cast.isCastSessionAvailable) playback.switchToCast()
        }

        playback.resuming = resumeLast && settings.resumeOnStart && settings.lastChannelId != 0L
        playback.ready = true

        // Come back to the last channel, the way a TV does.
        if (playback.resuming) viewModel.tune(settings.lastChannelId)
    }

    DisposableEffect(Unit) {
        onDispose {
            playback.castPlayer?.run {
                setSessionAvailabilityListener(null)
                release()
            }
            engine.release()
        }
    }

    // Live streams keep downloading while paused, so stop when the app is hidden
    // and rejoin at the live edge on return rather than hours behind. None of
    // this applies while casting: the TV carries on without the phone.
    val lifecycleOwner = LocalLifecycleOwner.current
    val inCatchup by rememberUpdatedState(state.catchup != null)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (playback.casting) return@LifecycleEventObserver
            val player = engine.player ?: return@LifecycleEventObserver
            val item = playback.current ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    player.pause()
                    item.progress?.let { viewModel.saveProgress(it, player.currentPosition, player.duration) }
                }
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) {
                    if (item.isLive && !inCatchup) player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(playback.ready, request?.token) {
        if (playback.ready) playback.load(request?.item)
    }

    // Seek-bar position, and a resume point saved every ten seconds.
    LaunchedEffect(playback.current?.key) {
        val item = playback.current ?: return@LaunchedEffect
        var sinceSave = 0
        while (isActive) {
            delay(500)
            val player = playback.activePlayer ?: continue
            playback.positionMs = player.currentPosition
            playback.durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            if (++sinceSave >= 20 && player.isPlaying) {
                sinceSave = 0
                item.progress?.let { viewModel.saveProgress(it, player.currentPosition, player.duration) }
            }
        }
    }

    if (autoTuneFirst) {
        LaunchedEffect(playback.ready, state.channels.isNotEmpty()) {
            if (playback.ready && !playback.resuming && state.currentChannel == null && state.vod == null) {
                state.channels.firstOrNull()?.let { viewModel.tune(it.channel) }
            }
        }
    }

    // Transient messages fade on their own; nothing here needs acknowledging.
    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            delay(6_000)
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
            // PlayerView ignores a repeat of the same player, so two surfaces
            // briefly sharing it (the mini player and the full page mid-
            // animation) do not steal the picture back and forth.
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

private fun isRecoverable(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> true
    else -> false
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
