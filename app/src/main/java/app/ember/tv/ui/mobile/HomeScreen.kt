package app.ember.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.tv.Graph
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.ProgressEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.ui.components.HSpace
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel
import app.ember.tv.ui.theme.Scrim

/** How many cards a category row shows before "See all" takes over. */
private const val ROW_LIMIT = 20

/** Sentinel passed to onBrowseGroup for the favourites list. */
const val FAVOURITES_GROUP = " favourites"

/**
 * Browse-first home: what you were in the middle of, then channels, then new
 * films and shows, then a row per live category. Nothing plays until tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    castAvailable: Boolean,
    onPlay: (ChannelWithNow) -> Unit,
    onResume: (ProgressEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
    onSearch: () -> Unit,
    onBrowseGroup: (String) -> Unit,
    onSeeAllMovies: () -> Unit,
    onSeeAllSeries: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val playlistId = state.activePlaylistId
    val continueWatching by remember(playlistId) { Graph.vod.continueWatching(playlistId) }
        .collectAsState(emptyList())
    val latestMovies by remember(playlistId) { Graph.vod.latestMovies(playlistId) }.collectAsState(emptyList())
    val latestSeries by remember(playlistId) { Graph.vod.latestSeries(playlistId) }.collectAsState(emptyList())

    val sections = remember(state.allChannels) {
        state.allChannels.groupBy { it.channel.groupName ?: "Other" }
    }
    val favourites = remember(state.allChannels) { state.allChannels.filter { it.isFavorite } }
    val playingId = state.currentChannel?.id
    val nothing = state.allChannels.isEmpty() && latestMovies.isEmpty() && latestSeries.isEmpty()

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refreshActive,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            item { HomeHeader(state, viewModel, castAvailable, onSearch, onOpenSettings) }

            if (nothing) {
                item {
                    EmptyState(
                        title = if (state.playlists.isEmpty()) "No playlist yet" else "Loading…",
                        detail = if (state.playlists.isEmpty())
                            "Add your Xtream login or an M3U playlist to get started." else
                            "Pull down to reload the playlist.",
                        action = if (state.playlists.isEmpty()) "Add a playlist" else null,
                        onAction = onOpenSettings,
                    )
                }
                return@LazyColumn
            }

            if (continueWatching.isNotEmpty()) {
                item { ContinueWatchingRow(continueWatching, onResume) }
            }
            if (state.recent.isNotEmpty()) {
                item { CardRow("Recently watched", state.recent, playingId, onPlay) }
            }
            if (favourites.isNotEmpty()) {
                item {
                    CardRow(
                        "Favourite channels", favourites.take(ROW_LIMIT), playingId, onPlay,
                        onSeeAll = if (favourites.size > ROW_LIMIT) ({ onBrowseGroup(FAVOURITES_GROUP) }) else null,
                    )
                }
            }
            if (latestMovies.isNotEmpty()) {
                item {
                    PosterRow(
                        title = "New movies",
                        items = latestMovies,
                        key = { it.id },
                        titleOf = { it.name },
                        posterOf = { it.poster },
                        captionOf = { it.year?.toString() },
                        onClick = onOpenMovie,
                        onSeeAll = onSeeAllMovies,
                    )
                }
            }
            if (latestSeries.isNotEmpty()) {
                item {
                    PosterRow(
                        title = "New series",
                        items = latestSeries,
                        key = { it.id },
                        titleOf = { it.name },
                        posterOf = { it.cover },
                        captionOf = { it.year?.toString() },
                        onClick = onOpenSeries,
                        onSeeAll = onSeeAllSeries,
                    )
                }
            }
            items(sections.keys.toList(), key = { "group:$it" }) { group ->
                CardRow(
                    title = group,
                    items = sections.getValue(group).take(ROW_LIMIT),
                    playingId = playingId,
                    onClick = onPlay,
                    onSeeAll = { onBrowseGroup(group) },
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    castAvailable: Boolean,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = state.playlists.firstOrNull { it.id == state.activePlaylistId }
    val canSwitch = state.playlists.size > 1

    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = canSwitch) { menuOpen = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = active?.name ?: "Ember",
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
        if (castAvailable) CastButton()
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
    }

    // A search field that is really a button: tapping it opens the search tab
    // with the keyboard up, which keeps this screen free of focus juggling.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Scrim.row)
            .clickable(onClick = onSearch)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        HSpace(8)
        Text(
            "Search channels, movies, series",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
