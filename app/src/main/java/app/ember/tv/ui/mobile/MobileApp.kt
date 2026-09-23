package app.ember.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.ui.components.HSpace
import app.ember.tv.ui.player.Playback
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel
import app.ember.tv.ui.player.VideoSurface
import app.ember.tv.ui.player.rememberPlayback
import app.ember.tv.ui.setup.SetupScreen
import app.ember.tv.ui.theme.Scrim

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LIVE("Live", Icons.Filled.LiveTv),
    MOVIES("Movies", Icons.Filled.Movie),
    SERIES("Series", Icons.Filled.VideoLibrary),
    SEARCH("Search", Icons.Filled.Search),
}

/**
 * Pages that open over the current tab. They are drawn on top of it rather
 * than replacing it, so a grid keeps its scroll position underneath a detail
 * page. Encoded as strings so they survive rotation.
 */
private object Page {
    fun movie(id: Long) = "movie:$id"
    fun series(id: Long) = "series:$id"
    fun group(name: String) = "group:$name"
    const val SETTINGS = "settings"
}

/**
 * The phone and tablet shell: five tabs, a mini-player that keeps the stream
 * going while browsing, detail pages over the tabs, and the full player page
 * sliding over everything.
 */
@Composable
fun MobileApp(viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback = rememberPlayback(viewModel, autoTuneFirst = false, resumeLast = false, enableCast = true)

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val playing = state.currentChannel != null || state.vod != null

    val playChannel: (ChannelWithNow) -> Unit = { row ->
        viewModel.tuneInGroup(row.channel)
        playerOpen = true
    }
    val openPlayer = { playerOpen = true }

    // Back peels one layer at a time: page, then tab, then out of the app.
    // (The player page and detail pages register their own, later handlers.)
    BackHandler(enabled = page == Page.SETTINGS || page?.startsWith("group:") == true) { page = null }
    BackHandler(enabled = page == null && tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (playing && !playerOpen) {
                    MiniPlayer(state, playback, viewModel, onOpen = openPlayer)
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                tab = t
                                page = null
                            },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Screens draw their own status-bar inset; only the bottom (tab bar,
        // mini player) comes from the scaffold, so nothing gets padded twice.
        val bottomOnly = PaddingValues(bottom = padding.calculateBottomPadding())

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = bottomOnly,
                    castAvailable = playback.castAvailable,
                    onPlay = playChannel,
                    onResume = { viewModel.resume(it); openPlayer() },
                    onOpenMovie = { page = Page.movie(it.id) },
                    onOpenSeries = { page = Page.series(it.id) },
                    onSearch = { tab = Tab.SEARCH },
                    onBrowseGroup = { page = Page.group(it) },
                    onSeeAllMovies = { tab = Tab.MOVIES },
                    onSeeAllSeries = { tab = Tab.SERIES },
                    onOpenSettings = { page = Page.SETTINGS },
                )
                Tab.LIVE -> MobileGuide(state, viewModel, bottomOnly, onPlay = playChannel, onOpenPlayer = openPlayer)
                Tab.MOVIES -> MoviesScreen(state, bottomOnly, onOpen = { page = Page.movie(it.id) })
                Tab.SERIES -> SeriesScreen(state, bottomOnly, onOpen = { page = Page.series(it.id) })
                Tab.SEARCH -> SearchScreen(
                    state = state,
                    viewModel = viewModel,
                    contentPadding = bottomOnly,
                    onPlay = playChannel,
                    onOpenMovie = { page = Page.movie(it.id) },
                    onOpenSeries = { page = Page.series(it.id) },
                )
            }

            val current = page
            AnimatedVisibility(
                visible = current != null,
                enter = slideInHorizontally { it / 3 } + fadeIn(),
                exit = slideOutHorizontally { it / 3 } + fadeOut(),
            ) {
                // Keep drawing the last page while it animates out.
                val shown = remember(current) { current } ?: return@AnimatedVisibility
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when {
                        shown.startsWith("movie:") -> MovieDetailPage(
                            movieId = shown.substringAfter(':').toLong(),
                            viewModel = viewModel,
                            contentPadding = bottomOnly,
                            onBack = { page = null },
                            onPlay = openPlayer,
                        )
                        shown.startsWith("series:") -> SeriesDetailPage(
                            seriesId = shown.substringAfter(':').toLong(),
                            viewModel = viewModel,
                            contentPadding = bottomOnly,
                            onBack = { page = null },
                            onPlay = openPlayer,
                        )
                        shown.startsWith("group:") -> BrowsePage(
                            group = shown.substringAfter(':'),
                            state = state,
                            viewModel = viewModel,
                            contentPadding = bottomOnly,
                            onBack = { page = null },
                            onPlay = playChannel,
                        )
                        shown == Page.SETTINGS -> SettingsPage(
                            contentPadding = bottomOnly,
                            onBack = { page = null },
                        )
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = playerOpen && playing,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        PlayerPage(state, playback, viewModel, onCollapse = { playerOpen = false })
    }
}

@Composable
private fun PageTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsPage(contentPadding: PaddingValues, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PageTopBar("Settings", onBack)
        SetupScreen(onDone = onBack, embedded = true)
    }
}

/** A single live category as a full list, reached from "See all" on the home screen. */
@Composable
private fun BrowsePage(
    group: String,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (ChannelWithNow) -> Unit,
) {
    val title = if (group == FAVOURITES_GROUP) "Favourite channels" else group
    val rows = remember(state.allChannels, group) {
        if (group == FAVOURITES_GROUP) state.allChannels.filter { it.isFavorite }
        else state.allChannels.filter { (it.channel.groupName ?: "Other") == group }
    }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(title, onBack)
        if (rows.isEmpty()) {
            EmptyState(title = "Nothing here")
        } else {
            LazyColumn(contentPadding = contentPadding) {
                items(rows, key = { it.channel.id }) { row ->
                    ChannelRow(
                        row = row,
                        playing = row.channel.id == state.currentChannel?.id,
                        showNumber = state.showChannelNumbers,
                        onClick = { onPlay(row) },
                        onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                    )
                }
            }
        }
    }
}

/**
 * Collapsed player above the tab bar, the way music apps do it, with a live
 * thumbnail of the picture so it is obvious something is still playing.
 */
@Composable
private fun MiniPlayer(
    state: PlayerUiState,
    playback: Playback,
    viewModel: PlayerViewModel,
    onOpen: () -> Unit,
) {
    val vod = state.vod
    val channel = state.currentChannel
    val title = vod?.title ?: channel?.name ?: return
    val subtitle = when {
        playback.casting -> playback.castDevice?.let { "Casting to $it" } ?: "Casting"
        vod != null -> vod.subtitle
        state.nowProgramme != null -> state.nowProgramme.title
        state.isBuffering -> "Connecting…"
        else -> "Live"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Scrim.panelDeep)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (playback.casting) {
                Icon(Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary)
            } else {
                VideoSurface(playback, state.aspectMode, Modifier.fillMaxSize())
            }
        }
        HSpace(10)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { playback.togglePlayPause() }) {
            Icon(
                if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
            )
        }
        IconButton(onClick = { viewModel.stop() }) {
            Icon(Icons.Filled.Close, "Stop")
        }
    }
}
