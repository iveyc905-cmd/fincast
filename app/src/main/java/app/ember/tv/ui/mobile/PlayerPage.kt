package app.ember.tv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.ember.tv.data.db.ChannelEntity
import app.ember.tv.ui.components.HSpace
import app.ember.tv.ui.components.ProgressLine
import app.ember.tv.ui.components.VSpace
import app.ember.tv.ui.components.formatClock
import app.ember.tv.ui.components.progressOf
import app.ember.tv.ui.player.Playback
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel
import app.ember.tv.ui.player.VideoSurface
import app.ember.tv.ui.theme.Scrim
import app.ember.tv.util.Pip
import app.ember.tv.util.findActivity
import kotlinx.coroutines.delay

/**
 * The full-screen "now watching" page that slides over the tabs.
 *
 * Portrait: video on top, details below. Landscape on a phone, or the
 * fullscreen button on a tablet: video only. Swiping the video down, the
 * chevron, or Back collapses it to the mini player without stopping playback.
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
    val playing = state.currentChannel != null || state.vod != null

    var showTracks by remember { mutableStateOf(false) }
    var showChannelSheet by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<ChannelEntity?>(null) }

    val setFullscreen: (Boolean) -> Unit = { on ->
        if (tablet) tabletFullscreen = on else requestOrientation(on)
    }

    ImmersiveMode(activity, fullscreen && !inPip)
    BackHandler { if (fullscreen) setFullscreen(false) else onCollapse() }

    // Leaving the app while this page is up shrinks the video into a floating
    // window. Not while casting: the picture is on the TV, not the phone.
    DisposableEffect(playing, playback.casting) {
        Pip.wanted = playing && !playback.casting
        activity?.let { Pip.applyParams(it) }
        onDispose {
            Pip.wanted = false
            activity?.let { Pip.applyParams(it) }
        }
    }

    if (inPip) {
        VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize().background(Color.Black))
        return
    }

    // Swipe-down-to-minimise, tracked in pixels while the finger is down.
    val density = LocalDensity.current
    val dismissPx = with(density) { 140.dp.toPx() }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val shownOffset by animateIntAsState(dragPx.toInt(), label = "drag")

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
            modifier = modifier.then(
                if (fullscreen) Modifier else Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragPx > dismissPx) onCollapse()
                            dragPx = 0f
                        },
                        onDragCancel = { dragPx = 0f },
                        onVerticalDrag = { _, dy -> dragPx = (dragPx + dy).coerceAtLeast(0f) },
                    )
                }
            ),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, shownOffset) }
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        if (fullscreen) {
            video(Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (state.vod != null) {
                    VodDetails(state, viewModel)
                } else {
                    NowPlayingDetails(state, viewModel, onSchedule = { scheduleFor = it })
                    UpNextInGroup(state, viewModel)
                }
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

    LaunchedEffect(controlsVisible, interactions, playback.isPlaying, playback.casting) {
        // While casting, the phone is a remote: keep the controls up.
        if (controlsVisible && playback.isPlaying && !playback.casting) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Box(modifier.background(Color.Black)) {
        if (playback.casting) {
            CastingBackdrop(state, playback)
        } else {
            VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize())
        }

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
            visible = controlsVisible || playback.casting,
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
                    .padding(bottom = if (fullscreen) 84.dp else 56.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim.panelDeep)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

/** What the video area shows while the stream is on a Cast device. */
@Composable
private fun CastingBackdrop(state: PlayerUiState, playback: Playback) {
    val art = state.vod?.poster ?: state.currentChannel?.logo
    val title = state.vod?.title ?: state.currentChannel?.name.orEmpty()
    Box(Modifier.fillMaxSize()) {
        Artwork(art, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            VSpace(6)
            Text(
                text = playback.castDevice?.let { "Playing on $it" } ?: "Casting",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
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
    val vod = state.vod
    // Films, episodes and catch-up can be scrubbed; live TV cannot.
    val seekable = vod != null || state.catchup != null
    val title = vod?.title ?: state.catchup?.programme?.title ?: state.currentChannel?.name.orEmpty()
    val subtitle = vod?.subtitle
        ?: state.catchup?.let { state.currentChannel?.name }
        ?: state.nowProgramme?.let { "${formatClock(it.startMs)}–${formatClock(it.stopMs)}  ${it.title}" }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.6f),
                    0.35f to Color.Black.copy(alpha = 0.25f),
                    0.65f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Black.copy(alpha = 0.7f),
                )
            )
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (playback.castAvailable) CastButton()
            if (!playback.casting) {
                IconButton(onClick = onTracks) {
                    Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White)
                }
                IconButton(onClick = { onTouched(); viewModel.cycleAspect() }) {
                    Icon(Icons.Filled.AspectRatio, "Aspect ratio", tint = Color.White)
                }
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seekable) {
                ControlButton(Icons.Filled.Replay10, "Back 10 seconds", 34.dp) {
                    onTouched(); playback.seekBy(-10_000)
                }
            } else {
                ControlButton(Icons.Filled.SkipPrevious, "Previous channel", 36.dp) {
                    onTouched(); viewModel.zap(-1)
                }
            }
            ControlButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = if (playback.isPlaying) "Pause" else "Play",
                size = 52.dp,
            ) { onTouched(); playback.togglePlayPause() }
            if (seekable) {
                ControlButton(Icons.Filled.Forward10, "Forward 10 seconds", 34.dp) {
                    onTouched(); playback.seekBy(10_000)
                }
            } else {
                ControlButton(Icons.Filled.SkipNext, "Next channel", 36.dp) {
                    onTouched(); viewModel.zap(1)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            if (seekable) SeekBar(playback, onTouched)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.catchup != null -> TextButton(onClick = { onTouched(); viewModel.returnToLive() }) {
                        Text("Back to live", color = Color.White)
                    }
                    vod == null -> LiveBadge()
                    else -> TimeLabel(playback)
                }
                Spacer(Modifier.weight(1f))
                if (vod?.next != null) {
                    TextButton(onClick = { onTouched(); viewModel.playNextEpisode() }) {
                        Text("Next episode", color = Color.White)
                        Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                    }
                }
                if (fullscreen && vod == null) {
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
}

@Composable
private fun LiveBadge() {
    Box(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFE53935))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun TimeLabel(playback: Playback) {
    Text(
        text = "${formatTime(playback.positionMs)} / ${formatTime(playback.durationMs)}",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** Scrubber that only seeks on release, so dragging does not hammer the stream. */
@Composable
private fun SeekBar(playback: Playback, onTouched: () -> Unit) {
    val duration = playback.durationMs
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubbing ?: if (duration > 0) playback.positionMs.toFloat() / duration else 0f
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = { scrubbing = it; onTouched() },
        onValueChangeFinished = {
            scrubbing?.let { if (duration > 0) playback.seekTo((it * duration).toLong()) }
            scrubbing = null
        },
        enabled = duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth().height(28.dp),
    )
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size))
    }
}

/** Film or episode details under the video in portrait. */
@Composable
private fun VodDetails(state: PlayerUiState, viewModel: PlayerViewModel) {
    val vod = state.vod ?: return
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(vod.title, style = MaterialTheme.typography.titleMedium)
                vod.subtitle?.let {
                    VSpace(2)
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vod.plot?.takeIf { it.isNotBlank() }?.let {
                    VSpace(10)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        vod.next?.let { next ->
            item {
                SectionHeader("Up next", null)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.playNextEpisode() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(128.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Artwork(next.still ?: vod.poster, "E${next.episode}", Modifier.fillMaxSize())
                    }
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "S${next.season} E${next.episode}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            next.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
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
