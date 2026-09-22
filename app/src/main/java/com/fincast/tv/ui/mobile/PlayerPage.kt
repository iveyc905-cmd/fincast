package com.fincast.tv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fincast.tv.data.db.ChannelEntity
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.components.ProgressLine
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.components.formatClock
import com.fincast.tv.ui.components.progressOf
import com.fincast.tv.ui.player.Playback
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.player.VideoSurface
import com.fincast.tv.ui.theme.Scrim
import com.fincast.tv.util.Pip
import com.fincast.tv.util.findActivity
import kotlinx.coroutines.delay

/**
 * The full-screen "now watching" page that slides over the tabs.
 *
 * Portrait: video on top with details and the rest of the category below.
 * Landscape on a phone, or the fullscreen button on a tablet: video only.
 * Collapsing it (the chevron or Back) keeps the stream going in the mini bar.
 */
@Composable
fun PlayerPage(
    state: PlayerUiState,
    playback: Playback,
    viewModel: PlayerViewModel,
    onCollapse: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = config.smallestScreenWidthDp >= 600
    var tabletFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = if (tablet) tabletFullscreen else landscape
    val requestOrientation = rememberOrientationRequest(activity)
    val inPip = Pip.inPip

    var showTracks by remember { mutableStateOf(false) }
    var showChannelSheet by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<ChannelEntity?>(null) }

    val setFullscreen: (Boolean) -> Unit = { on ->
        if (tablet) tabletFullscreen = on else requestOrientation(on)
    }

    ImmersiveMode(activity, fullscreen && !inPip)
    BackHandler { if (fullscreen) setFullscreen(false) else onCollapse() }

    // Leaving the app while this page is up should shrink the video to a
    // floating window instead of stopping it.
    DisposableEffect(state.currentChannel?.id) {
        Pip.wanted = state.currentChannel != null
        activity?.let { Pip.applyParams(it) }
        onDispose {
            Pip.wanted = false
            activity?.let { Pip.applyParams(it) }
        }
    }

    if (inPip) {
        // Nothing but the picture fits in a PiP window.
        VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val video: @Composable (Modifier) -> Unit = { modifier ->
        VideoArea(
            state = state,
            playback = playback,
            viewModel = viewModel,
            fullscreen = fullscreen,
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            onTracks = { showTracks = true },
            onChannels = { showChannelSheet = true },
            onCollapse = onCollapse,
            modifier = modifier,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        if (fullscreen) {
            video(Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                NowPlayingDetails(state, viewModel, onSchedule = { scheduleFor = it })
                UpNextInGroup(state, viewModel)
            }
        }
    }

    if (showTracks) TracksDialog(playback.engine, onDismiss = { showTracks = false })
    if (showChannelSheet) {
        ChannelSheet(state, viewModel, onOpenSettings = {}, onDismiss = { showChannelSheet = false })
    }
    scheduleFor?.let { channel ->
        ScheduleSheet(channel, state, viewModel, onDismiss = { scheduleFor = null })
    }
}

@Composable
private fun VideoArea(
    state: PlayerUiState,
    playback: Playback,
    viewModel: PlayerViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTracks: () -> Unit,
    onChannels: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on every control tap so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }
    val touched: () -> Unit = { interactions++ }

    LaunchedEffect(controlsVisible, interactions, playback.isPlaying) {
        if (controlsVisible && playback.isPlaying) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Box(modifier.background(Color.Black)) {
        VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize())

        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible }
        )

        if (state.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                state = state,
                playback = playback,
                viewModel = viewModel,
                fullscreen = fullscreen,
                onToggleFullscreen = { touched(); onToggleFullscreen() },
                onTracks = { touched(); onTracks() },
                onChannels = { touched(); onChannels() },
                onCollapse = onCollapse,
                onTouched = touched,
            )
        }

        state.statusMessage?.let { message ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (fullscreen) 72.dp else 48.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim.panelDeep)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    playback: Playback,
    viewModel: PlayerViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTracks: () -> Unit,
    onChannels: () -> Unit,
    onCollapse: () -> Unit,
    onTouched: () -> Unit,
) {
    val channel = state.currentChannel
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .then(if (fullscreen) Modifier.displayCutoutPadding() else Modifier)
    ) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, "Minimise", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.nowProgramme?.let { now ->
                    Text(
                        text = "${formatClock(now.startMs)}–${formatClock(now.stopMs)}  ${now.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onTracks) {
                Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White)
            }
            IconButton(onClick = { onTouched(); viewModel.cycleAspect() }) {
                Icon(Icons.Filled.AspectRatio, "Aspect ratio", tint = Color.White)
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(Icons.Filled.SkipPrevious, "Previous channel", 36.dp) {
                onTouched(); viewModel.zap(-1)
            }
            ControlButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = if (playback.isPlaying) "Pause" else "Play",
                size = 52.dp,
            ) { onTouched(); playback.togglePlayPause() }
            ControlButton(Icons.Filled.SkipNext, "Next channel", 36.dp) {
                onTouched(); viewModel.zap(1)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.catchup != null) {
                TextButton(onClick = { onTouched(); viewModel.returnToLive() }) {
                    Text("Back to live", color = Color.White)
                }
            } else {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (fullscreen) {
                IconButton(onClick = onChannels) {
                    Icon(Icons.AutoMirrored.Filled.List, "Channels", tint = Color.White)
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun NowPlayingDetails(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onSchedule: (ChannelEntity) -> Unit,
) {
    val channel = state.currentChannel ?: return
    val color = rememberChannelColor(channel.logo, channel.name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) { LogoOrInitials(channel.logo, channel.name, Modifier.fillMaxSize(0.7f)) }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val now = state.nowProgramme
            Text(
                text = now?.let { "${formatClock(it.startMs)}–${formatClock(it.stopMs)}  ${it.title}" }
                    ?: "No programme info",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                VSpace(4)
                ProgressLine(progressOf(now.startMs, now.stopMs), Modifier.fillMaxWidth())
            }
        }
        val favorite = state.allChannels.firstOrNull { it.channel.id == channel.id }?.isFavorite == true
        FavoriteButton(favorite) { viewModel.toggleFavorite(channel.id) }
        IconButton(onClick = { onSchedule(channel) }) {
            Icon(Icons.Filled.Schedule, "Schedule and catch-up")
        }
    }
    state.nowProgramme?.description?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The rest of the current category, so the next thing to watch is one tap away. */
@Composable
private fun UpNextInGroup(state: PlayerUiState, viewModel: PlayerViewModel) {
    val others = state.channels.filter { it.channel.id != state.currentChannel?.id }
    if (others.isEmpty()) return
    Text(
        text = state.selectedGroup ?: if (state.favoritesOnly) "Favourites" else "All channels",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(others, key = { it.channel.id }) { row ->
            ChannelRow(
                row = row,
                playing = false,
                showNumber = state.showChannelNumbers,
                onClick = { viewModel.tune(row.channel) },
                onFavorite = { viewModel.toggleFavorite(row.channel.id) },
            )
        }
    }
}

/** Hides the status and navigation bars while fullscreen; a swipe brings them back briefly. */
@Composable
private fun ImmersiveMode(activity: Activity?, enabled: Boolean) {
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (enabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/**
 * Returns a function that rotates the screen on request, YouTube-style.
 *
 * Tapping fullscreen locks landscape so the rotation happens immediately, but
 * the lock is released as soon as the phone is physically turned to match, so
 * auto-rotate carries on working afterwards. Exiting works the same way in
 * reverse.
 */
@Composable
private fun rememberOrientationRequest(activity: Activity?): (Boolean) -> Unit {
    val context = LocalContext.current
    // true = waiting for the device to be held sideways; false = upright.
    var waitingFor by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(waitingFor) {
        val target = waitingFor
        if (target == null || activity == null) return@DisposableEffect onDispose {}
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val sideways = degrees in 60..120 || degrees in 240..300
                val upright = degrees <= 30 || degrees >= 330 || degrees in 150..210
                if ((target && sideways) || (!target && upright)) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    waitingFor = null
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    return { landscape ->
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        waitingFor = landscape
    }
}
