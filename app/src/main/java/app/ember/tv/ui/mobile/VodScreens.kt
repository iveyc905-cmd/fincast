package app.ember.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.tv.Graph
import app.ember.tv.data.db.EpisodeEntity
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.ProgressEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.data.model.MovieInfo
import app.ember.tv.data.repo.VodRepository
import app.ember.tv.ui.components.HSpace
import app.ember.tv.ui.components.ProgressLine
import app.ember.tv.ui.components.VSpace
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel

// --- Browse grids -----------------------------------------------------------

@Composable
fun MoviesScreen(
    state: PlayerUiState,
    contentPadding: PaddingValues,
    onOpen: (MovieEntity) -> Unit,
) {
    val playlistId = state.activePlaylistId
    var category by rememberSaveable(playlistId) { mutableStateOf<String?>(null) }
    val categories by remember(playlistId) { Graph.vod.movieCategories(playlistId) }.collectAsState(emptyList())
    val movies by remember(playlistId, category) { Graph.vod.movies(playlistId, category) }.collectAsState(null)

    VodGrid(
        title = "Movies",
        categories = categories,
        category = category,
        onCategory = { category = it },
        items = movies,
        emptyTitle = "No movies in this playlist",
        emptyDetail = "Movies appear here when your provider includes them. Xtream logins list them automatically.",
        contentPadding = contentPadding,
        key = { it.id },
    ) { movie ->
        PosterCard(
            title = movie.name,
            poster = movie.poster,
            caption = listOfNotNull(movie.year?.toString(), movie.rating?.let { "★ %.1f".format(it) }).joinToString("  "),
            onClick = { onOpen(movie) },
        )
    }
}

@Composable
fun SeriesScreen(
    state: PlayerUiState,
    contentPadding: PaddingValues,
    onOpen: (SeriesEntity) -> Unit,
) {
    val playlistId = state.activePlaylistId
    var category by rememberSaveable(playlistId) { mutableStateOf<String?>(null) }
    val categories by remember(playlistId) { Graph.vod.seriesCategories(playlistId) }.collectAsState(emptyList())
    val shows by remember(playlistId, category) { Graph.vod.series(playlistId, category) }.collectAsState(null)

    VodGrid(
        title = "Series",
        categories = categories,
        category = category,
        onCategory = { category = it },
        items = shows,
        emptyTitle = "No series in this playlist",
        emptyDetail = "Series appear here when your provider includes them.",
        contentPadding = contentPadding,
        key = { it.id },
    ) { show ->
        PosterCard(
            title = show.name,
            poster = show.cover,
            caption = listOfNotNull(show.year?.toString(), show.rating?.let { "★ %.1f".format(it) }).joinToString("  "),
            onClick = { onOpen(show) },
        )
    }
}

@Composable
private fun <T> VodGrid(
    title: String,
    categories: List<String>,
    category: String?,
    onCategory: (String?) -> Unit,
    items: List<T>?,
    emptyTitle: String,
    emptyDetail: String,
    contentPadding: PaddingValues,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!items.isNullOrEmpty()) {
                HSpace(8)
                Text(
                    "${items.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        if (categories.isNotEmpty()) CategoryChips(categories, category, onCategory)

        when {
            items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> EmptyState(title = emptyTitle, detail = emptyDetail)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = key) { card(it) }
            }
        }
    }
}

@Composable
private fun CategoryChips(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") }, colors = colors)
        }
        items(categories) { c ->
            FilterChip(selected = selected == c, onClick = { onSelect(c) }, label = { Text(c, maxLines = 1) }, colors = colors)
        }
    }
}

// --- Detail pages -----------------------------------------------------------

/** Backdrop header shared by the film and series pages. */
@Composable
private fun DetailHeader(image: String?, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
        Artwork(image, title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.35f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    )
                )
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
    }
}

@Composable
private fun MetaLine(parts: List<String?>) {
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun ExpandableText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun resumable(p: ProgressEntity?) =
    p != null && p.positionMs > 60_000 && (p.durationMs <= 0 || p.positionMs < p.durationMs * 0.95)

@Composable
fun MovieDetailPage(
    movieId: Long,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val movie by produceState<MovieEntity?>(null, movieId) { value = Graph.vod.movie(movieId) }
    val m = movie ?: return
    val info by produceState<MovieInfo?>(null, m.id) { value = Graph.vod.movieInfo(m) }
    val progressList by remember(m.id) { Graph.vod.progressFor(listOf(VodRepository.movieKey(m))) }
        .collectAsState(emptyList())
    val progress = progressList.firstOrNull()

    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item { DetailHeader(info?.backdrop ?: m.poster, m.name, onBack) }
        item {
            Text(
                m.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            VSpace(4)
            MetaLine(
                listOf(
                    (info?.year ?: m.year)?.toString(),
                    formatDuration(info?.durationSec),
                    (info?.rating ?: m.rating)?.let { "★ %.1f".format(it) },
                    info?.genre,
                )
            )
            VSpace(16)
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { viewModel.playMovie(m); onPlay() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    HSpace(6)
                    Text(if (resumable(progress)) "Resume" else "Play")
                }
                if (resumable(progress)) {
                    OutlinedButton(onClick = { viewModel.playMovie(m, fromStart = true); onPlay() }) {
                        Icon(Icons.Filled.Replay, null)
                        HSpace(6)
                        Text("Start over")
                    }
                }
            }
            if (resumable(progress) && progress!!.durationMs > 0) {
                VSpace(10)
                ProgressLine(
                    progress.positionMs.toFloat() / progress.durationMs,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
            VSpace(8)
            info?.plot?.let { ExpandableText(it) }
            info?.cast?.let { Credit("Cast", it) }
            info?.director?.let { Credit("Director", it) }
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SeriesDetailPage(
    seriesId: Long,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val show by remember(seriesId) { Graph.vod.observeShow(seriesId) }.collectAsState(null)
    val episodes by remember(seriesId) { Graph.vod.episodes(seriesId) }.collectAsState(emptyList())
    var loadError by remember(seriesId) { mutableStateOf<String?>(null) }

    LaunchedEffect(seriesId) {
        Graph.vod.ensureEpisodes(seriesId).onFailure { loadError = it.message ?: "Could not load episodes" }
    }

    val s = show ?: return
    val keys = remember(episodes) { episodes.map { VodRepository.episodeKey(s.playlistId, it) } }
    val progressList by remember(keys) { Graph.vod.progressFor(keys) }.collectAsState(emptyList())
    val progressByUrl = remember(progressList) { progressList.associateBy { it.url } }

    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    // Open on the season of whatever was watched last.
    val lastWatched = remember(progressList) { progressList.maxByOrNull { it.updatedMs } }
    val lastEpisode = remember(lastWatched, episodes) { episodes.firstOrNull { it.url == lastWatched?.url } }
    var season by rememberSaveable(seriesId) { mutableStateOf<Int?>(null) }
    val activeSeason = season ?: lastEpisode?.season ?: seasons.firstOrNull()

    // The big button continues where the viewer is: the half-watched episode,
    // the one after a finished one, or the very first.
    val upNext: EpisodeEntity? = remember(lastEpisode, lastWatched, episodes) {
        when {
            lastEpisode == null -> episodes.firstOrNull()
            resumable(lastWatched) -> lastEpisode
            else -> episodes.getOrNull(episodes.indexOf(lastEpisode) + 1) ?: lastEpisode
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item { DetailHeader(s.cover, s.name, onBack) }
        item {
            Text(s.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            VSpace(4)
            MetaLine(
                listOf(
                    s.year?.toString(),
                    if (seasons.size > 1) "${seasons.size} seasons" else null,
                    s.rating?.let { "★ %.1f".format(it) },
                    s.category,
                )
            )
            VSpace(16)
            upNext?.let { ep ->
                Button(
                    onClick = { viewModel.playEpisode(s, ep); onPlay() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    HSpace(6)
                    val verb = if (resumable(progressByUrl[ep.url])) "Resume" else "Play"
                    Text("$verb S${ep.season} E${ep.episode}")
                }
            }
            s.plot?.let { VSpace(6); ExpandableText(it) }
            if (seasons.size > 1) {
                val colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seasons) { n ->
                        FilterChip(
                            selected = n == activeSeason,
                            onClick = { season = n },
                            label = { Text("Season $n") },
                            colors = colors,
                        )
                    }
                }
            }
        }

        when {
            episodes.isEmpty() && loadError != null -> item {
                EmptyState(title = "Couldn't load episodes", detail = loadError)
            }
            episodes.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> items(episodes.filter { it.season == activeSeason }, key = { it.id }) { ep ->
                EpisodeRow(
                    episode = ep,
                    fallbackArt = s.cover,
                    progress = progressByUrl[ep.url],
                    onClick = { viewModel.playEpisode(s, ep); onPlay() },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    fallbackArt: String?,
    progress: ProgressEntity?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Artwork(episode.still ?: fallbackArt, "E${episode.episode}", Modifier.fillMaxSize())
            Icon(
                Icons.Filled.PlayArrow, null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(3.dp),
            )
            if (progress != null && progress.durationMs > 0 && progress.positionMs > 0) {
                ProgressLine(
                    (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f),
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp),
                )
            }
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(
                "${episode.episode}. ${episode.title}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            formatDuration(episode.durationSec)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            episode.plot?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
