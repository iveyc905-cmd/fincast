package app.ember.tv.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.ember.tv.Graph
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.flowOf

/** How many films or shows to show inline before the list gets unwieldy. */
private const val VOD_RESULTS = 30

@Composable
fun SearchScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onPlay: (ChannelWithNow) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    val query = state.searchQuery.trim()
    val playlistId = state.activePlaylistId
    val movies by remember(playlistId, query) {
        if (query.isEmpty()) flowOf(emptyList()) else Graph.vod.movies(playlistId, null, query)
    }.collectAsState(emptyList())
    val shows by remember(playlistId, query) {
        if (query.isEmpty()) flowOf(emptyList()) else Graph.vod.series(playlistId, null, query)
    }.collectAsState(emptyList())

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder = { Text("Channels, movies, series") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(focus),
        )

        val noResults = state.searchResults.isEmpty() && movies.isEmpty() && shows.isEmpty()
        when {
            query.isEmpty() -> EmptyState(
                title = "Find something to watch",
                detail = "Search every channel, movie and series in this playlist.",
            )
            noResults -> EmptyState(title = "Nothing matches \"$query\"")
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                if (movies.isNotEmpty()) {
                    item {
                        PosterRow(
                            title = "Movies",
                            items = movies.take(VOD_RESULTS),
                            key = { it.id },
                            titleOf = { it.name },
                            posterOf = { it.poster },
                            captionOf = { it.year?.toString() },
                            onClick = { keyboard?.hide(); onOpenMovie(it) },
                        )
                    }
                }
                if (shows.isNotEmpty()) {
                    item {
                        PosterRow(
                            title = "Series",
                            items = shows.take(VOD_RESULTS),
                            key = { it.id },
                            titleOf = { it.name },
                            posterOf = { it.cover },
                            captionOf = { it.year?.toString() },
                            onClick = { keyboard?.hide(); onOpenSeries(it) },
                        )
                    }
                }
                if (state.searchResults.isNotEmpty()) {
                    item { SectionHeader("Channels", null) }
                    items(state.searchResults, key = { "ch:${it.channel.id}" }) { row ->
                        ChannelRow(
                            row = row,
                            playing = row.channel.id == state.currentChannel?.id,
                            showNumber = false,
                            onClick = { keyboard?.hide(); onPlay(row) },
                            onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                        )
                    }
                }
            }
        }
    }
}
