package com.fincast.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fincast.tv.Graph
import com.fincast.tv.data.repo.AspectMode
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.guide.GuideOverlay
import com.fincast.tv.ui.theme.Scrim

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onOpenSettings: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val engine = remember { PlayerEngine(context) }

    // The player is built from stored preferences, so nothing can be tuned until
    // that has happened — `ready` is what keeps the two effects below in order.
    var ready by remember { mutableStateOf(false) }

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

            override fun onPlayerError(error: PlaybackException) {
                viewModel.reportError(friendlyError(error))
            }
        })
        ready = true

        // Resume where the user left off, the way a TV returns to its last channel.
        if (settings.resumeOnStart && settings.lastChannelId != 0L) {
            viewModel.tune(settings.lastChannelId)
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    // Live tuning: whenever the channel changes, play its URL.
    LaunchedEffect(ready, state.currentChannel?.id) {
        if (!ready) return@LaunchedEffect
        val channel = state.currentChannel ?: return@LaunchedEffect
        if (state.catchup == null) {
            engine.play(channel.url, channel.referrer)
        }
    }

    // Catch-up and return-to-live push an explicit URL instead.
    LaunchedEffect(ready, pending) {
        if (!ready) return@LaunchedEffect
        pending?.let { (url, referrer) ->
            engine.play(url, referrer)
            viewModel.consumedPendingUrl()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(event.key.nativeKeyCode, state, viewModel, onOpenSettings)
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // The app draws its own overlays; the stock controller would
                    // fight them for D-pad focus.
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = engine.player
                view.resizeMode = when (state.aspectMode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (state.overlay) {
            Overlay.CHANNELS -> ChannelPanel(state, viewModel)
            Overlay.GUIDE -> GuideOverlay(state, viewModel)
            Overlay.INFO -> InfoBar(state, viewModel)
            Overlay.TRACKS -> TrackPanel(engine, viewModel)
            Overlay.MENU -> MenuPanel(state, viewModel, onOpenSettings)
            Overlay.NONE -> Unit
        }

        // Direct channel entry floats above everything, like a tuner OSD.
        if (state.numberEntry.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim.panelDeep)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(state.numberEntry, style = MaterialTheme.typography.titleLarge)
            }
        }

        state.statusMessage?.let { message ->
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Scrim.panelDeep)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                VSpace(4)
            }
        }
    }
}

/**
 * Central remote-control map.
 *
 * Overlays consume their own navigation keys; this only sees what they ignore,
 * which keeps the bindings for "watching fullscreen" in one readable place.
 */
private fun handleKey(
    keyCode: Int,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSettings: () -> Unit,
): Boolean {
    // Digits work from any state — typing a number always means "tune there".
    if (keyCode in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) {
        viewModel.pushDigit(('0' + (keyCode - AndroidKeyEvent.KEYCODE_0)))
        return true
    }

    if (state.overlay != Overlay.NONE) {
        return when (keyCode) {
            AndroidKeyEvent.KEYCODE_BACK -> {
                viewModel.closeOverlay(); true
            }
            else -> false // let the overlay handle it
        }
    }

    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
            viewModel.zap(-1); true
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
            viewModel.zap(1); true
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (state.numberEntry.isNotEmpty()) viewModel.commitNumberEntry()
            else viewModel.openOverlay(Overlay.CHANNELS)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
            viewModel.openOverlay(Overlay.INFO); true
        }
        AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_CONTENTS_MENU,
        AndroidKeyEvent.KEYCODE_PROG_YELLOW -> {
            viewModel.openOverlay(Overlay.GUIDE); true
        }
        AndroidKeyEvent.KEYCODE_INFO -> {
            viewModel.openOverlay(Overlay.INFO); true
        }
        AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> {
            viewModel.openOverlay(Overlay.MENU); true
        }
        AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_PROG_BLUE -> {
            viewModel.openOverlay(Overlay.TRACKS); true
        }
        AndroidKeyEvent.KEYCODE_PROG_RED, AndroidKeyEvent.KEYCODE_BOOKMARK -> {
            viewModel.toggleFavorite(); true
        }
        AndroidKeyEvent.KEYCODE_PROG_GREEN, AndroidKeyEvent.KEYCODE_ZOOM_IN -> {
            viewModel.cycleAspect(); true
        }
        AndroidKeyEvent.KEYCODE_LAST_CHANNEL, AndroidKeyEvent.KEYCODE_TV_TELETEXT -> {
            viewModel.recallPrevious(); true
        }
        else -> false
    }
}

private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
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

    else -> error.errorCodeName
}
