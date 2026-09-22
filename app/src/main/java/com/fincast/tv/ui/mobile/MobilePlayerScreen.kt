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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fincast.tv.data.db.ChannelEntity
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.ui.components.ChannelLogo
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.components.ProgressLine
import com.fincast.tv.ui.components.TwoLine
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.components.formatClock
import com.fincast.tv.ui.components.progressOf
import com.fincast.tv.ui.player.Playback
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.player.VideoSurface
import com.fincast.tv.ui.player.rememberPlayback
import com.fincast.tv.ui.theme.Scrim
import com.fincast.tv.util.findActivity
import kotlinx.coroutines.delay

/**
 * The touch layout for phones and tablets.
 *
 * Portrait: video on top, the channel list underneath — tap a channel to play it.
 * Landscape on a phone: the video goes fullscreen, like every other video app.
 * Landscape on a tablet: video and list side by side, with a fullscreen button.
 */
@Composable
fun MobilePlayerScreen(
    onOpenSettings: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback = rememberPlayback(viewModel, autoTuneFirst = false)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = config.smallestScreenWidthDp >= 600
    var tabletFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = if (tablet) tabletFullscreen else landscape
    val requestOrientation = rememberOrientationRequest(activity)

    var showTracks by remember { mutableStateOf(false) }
    var showChannelSheet by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<ChannelEntity?>(null) }

    val setFullscreen: (Boolean) -> Unit = { on ->
        if (tablet) tabletFullscreen = on else requestOrientation(on)
    }

    ImmersiveMode(activity, fullscreen)
    BackHandler(enabled = fullscreen) { setFullscreen(false) }

    val video: @Composable (Modifier) -> Unit = { modifier ->
        VideoArea(
            state = state,
            playback = playback,
            viewModel = viewModel,
            fullscreen = fullscreen,
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            onTracks = { showTracks = true },
            onChannels = { showChannelSheet = true },
            modifier = modifier,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        if (tablet && landscape && !fullscreen) {
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    NowPlayingPanel(state, viewModel, onSchedule = { scheduleFor = it })
                }
                Column(Modifier.weight(0.42f).fillMaxHeight()) {
                    TopBar(state, viewModel, onOpenSettings)
                    ChannelBrowser(
                        state = state,
                        viewModel = viewModel,
                        onOpenSettings = onOpenSettings,
                        onPicked = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            // One column for both portrait and fullscreen, so the video keeps its
            // place in the tree and is not rebuilt on rotation.
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (fullscreen) Modifier else Modifier.systemBarsPadding())
            ) {
                if (!fullscreen) TopBar(state, viewModel, onOpenSettings)
                video(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                if (!fullscreen) {
                    NowPlayingPanel(state, viewModel, onSchedule = { scheduleFor = it })
                    ChannelBrowser(
                        state = state,
                        viewModel = viewModel,
                        onOpenSettings = onOpenSettings,
                        onPicked = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showTracks) TracksDialog(playback.engine, onDismiss = { showTracks = false })
    if (showChannelSheet) {
        ChannelSheet(state, viewModel, onOpenSettings, onDismiss = { showChannelSheet = false })
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
    modifier: Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on every control tap so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }
    val touched = { interactions++ }

    LaunchedEffect(controlsVisible, interactions, playback.isPlaying) {
        if (controlsVisible && playback.isPlaying) {
            delay(4_000)
            controlsVisible = false
        }
    }

    Box(modifier.background(Color.Black)) {
        VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize())

        // Tapping anywhere on the picture toggles the controls.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible }
        )

        if (state.currentChannel == null) {
            Text(
                text = "Pick a channel to start watching",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && state.currentChannel != null,
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
                .padding(start = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel?.let { "${it.number}  ${it.name}" }.orEmpty(),
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
private fun NowPlayingPanel(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onSchedule: (ChannelEntity) -> Unit,
) {
    val channel = state.currentChannel
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel == null) {
            Text(
                "Choose a channel below",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            ChannelLogo(channel.logo, channel.name, size = 40.dp)
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
            val favorite = state.channels.firstOrNull { it.channel.id == channel.id }?.isFavorite == true
            FavoriteButton(favorite) { viewModel.toggleFavorite(channel.id) }
            IconButton(onClick = { onSchedule(channel) }) {
                Icon(Icons.Filled.Schedule, "Schedule and catch-up")
            }
        }
    }
}

@Composable
private fun TopBar(state: PlayerUiState, viewModel: PlayerViewModel, onOpenSettings: () -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(enabled = searching) {
        viewModel.setQuery("")
        searching = false
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            IconButton(onClick = {
                viewModel.setQuery("")
                searching = false
            }) { Icon(Icons.Filled.Close, "Close search") }
        } else {
            val active = state.playlists.firstOrNull { it.id == state.activePlaylistId }
            val canSwitch = state.playlists.size > 1
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canSwitch) { menuOpen = true }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = active?.name ?: "Fincast",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (canSwitch) Icon(Icons.Filled.ArrowDropDown, "Switch playlist")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text(playlist.name) },
                            onClick = {
                                viewModel.selectPlaylist(playlist.id)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }
    }
}

/** Category chips plus the scrolling channel list. Shared by the main screen and the fullscreen sheet. */
@Composable
internal fun ChannelBrowser(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSettings: () -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        GroupChips(state, viewModel)

        val nothingLoaded = state.playlists.isEmpty() ||
            (state.channels.isEmpty() && state.selectedGroup == null &&
                !state.favoritesOnly && state.query.isBlank())

        when {
            nothingLoaded -> EmptyState(
                title = "No channels loaded yet",
                detail = "Add your Xtream login or an M3U playlist to get started.",
                action = "Add a playlist",
                onAction = onOpenSettings,
            )

            state.channels.isEmpty() && state.favoritesOnly -> EmptyState(
                title = "No favourites yet",
                detail = "Tap the star next to a channel to keep it here.",
            )

            state.channels.isEmpty() -> EmptyState(title = "No matching channels")

            else -> {
                val listState = rememberLazyListState()
                // Open on the playing channel rather than the top of a long list.
                LaunchedEffect(Unit) {
                    val index = state.currentIndex
                    if (index > 2) listState.scrollToItem(index - 2)
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.channels, key = { it.channel.id }) { row ->
                        ChannelRow(
                            row = row,
                            playing = row.channel.id == state.currentChannel?.id,
                            showNumber = state.showChannelNumbers,
                            onClick = {
                                viewModel.tune(row.channel)
                                onPicked()
                            },
                            onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupChips(state: PlayerUiState, viewModel: PlayerViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.selectedGroup == null && !state.favoritesOnly,
                onClick = {
                    viewModel.setFavoritesOnly(false)
                    viewModel.setGroup(null)
                },
                label = { Text("All") },
            )
        }
        item {
            FilterChip(
                selected = state.favoritesOnly,
                onClick = {
                    viewModel.setGroup(null)
                    viewModel.setFavoritesOnly(true)
                },
                label = { Text("Favourites") },
                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) },
            )
        }
        items(state.groups) { group ->
            FilterChip(
                selected = state.selectedGroup == group && !state.favoritesOnly,
                onClick = {
                    viewModel.setFavoritesOnly(false)
                    viewModel.setGroup(group)
                },
                label = { Text(group, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ChannelRow(
    row: ChannelWithNow,
    playing: Boolean,
    showNumber: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (playing) Scrim.rowSelected.copy(alpha = 0.55f) else Color.Transparent)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                text = row.channel.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
        }
        ChannelLogo(row.channel.logo, row.channel.name, size = 44.dp)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TwoLine(primary = row.channel.name, secondary = row.nowTitle ?: "No programme info")
            if (row.nowStartMs != null && row.nowStopMs != null) {
                VSpace(4)
                ProgressLine(progressOf(row.nowStartMs, row.nowStopMs))
            }
        }
        FavoriteButton(row.isFavorite, onFavorite)
    }
}

@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
            tint = if (favorite) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    detail: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            VSpace(6)
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            VSpace(16)
            Button(onClick = onAction) { Text(action) }
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
