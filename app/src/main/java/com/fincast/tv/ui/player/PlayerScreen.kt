package com.fincast.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.guide.GuideOverlay
import com.fincast.tv.ui.theme.Scrim

/** The remote-driven layout for Android TV and Fire TV. */
@Composable
fun TvPlayerScreen(
    onOpenSettings: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback = rememberPlayback(viewModel, autoTuneFirst = true)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(event.key.nativeKeyCode, state, viewModel)
            }
    ) {
        VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize())

        if (state.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val nothingLoaded = state.channels.isEmpty() && state.selectedGroup == null &&
            !state.favoritesOnly && state.query.isEmpty()
        if (nothingLoaded && state.overlay == Overlay.NONE) {
            Text(
                text = "No channels loaded yet.\nPress Menu, then Settings & playlists, to add one.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        when (state.overlay) {
            Overlay.CHANNELS -> ChannelPanel(state, viewModel)
            Overlay.GUIDE -> GuideOverlay(state, viewModel)
            Overlay.INFO -> InfoBar(state, viewModel)
            Overlay.TRACKS -> TrackPanel(playback.engine, viewModel)
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
): Boolean {
    // Digits work from any state — typing a number always means "tune there".
    if (keyCode in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) {
        viewModel.pushDigit('0' + (keyCode - AndroidKeyEvent.KEYCODE_0))
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
