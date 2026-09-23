package app.ember.tv.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import app.ember.tv.data.db.AppDatabase
import app.ember.tv.data.db.ChannelEntity
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.data.db.EpisodeEntity
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.PlaylistEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.data.model.ParsedChannel
import app.ember.tv.data.model.ParsedEpisode
import app.ember.tv.data.model.ParsedMovie
import app.ember.tv.data.model.ParsedPlaylist
import app.ember.tv.data.model.ParsedSeries
import app.ember.tv.data.model.RefreshSummary
import app.ember.tv.data.model.SourceKind
import app.ember.tv.data.net.Http
import app.ember.tv.data.net.XtreamClient
import app.ember.tv.data.parse.M3uParser
import app.ember.tv.data.parse.VodNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

class PlaylistRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    private val settings: SettingsRepository = SettingsRepository(context),
) {
    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    fun observeGroups(playlistId: Long): Flow<List<String>> =
        db.channels().observeGroups(playlistId)

    fun observeChannels(
        playlistId: Long,
        group: String?,
        favoritesOnly: Boolean,
        query: String,
        now: Long = System.currentTimeMillis(),
    ): Flow<List<ChannelWithNow>> = db.channels().observeChannels(
        playlistId = playlistId,
        group = group,
        favoritesOnly = favoritesOnly,
        likeQuery = query.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" },
        now = now,
    )

    fun observeRecent(playlistId: Long, limit: Int = 20, now: Long = System.currentTimeMillis()) =
        db.channels().observeRecent(playlistId, now, limit)

    suspend fun channel(id: Long): ChannelEntity? = db.channels().byId(id)

    suspend fun channelByNumber(playlistId: Long, number: Int): ChannelEntity? =
        db.channels().byNumber(playlistId, number)

    suspend fun toggleFavorite(channelId: Long) = db.favorites().toggle(channelId)

    suspend fun recordWatch(channelId: Long) {
        db.watchState().record(channelId)
        settings.setLastChannel(channelId)
    }

    /** @return what was loaded; throws if the playlist could not be loaded. */
    suspend fun addM3uUrl(name: String, url: String, userAgent: String?): RefreshSummary {
        val playlist = PlaylistEntity(
            name = name.ifBlank { Uri.parse(url).host ?: "Playlist" },
            kind = SourceKind.M3U_URL,
            source = url.trim(),
            userAgent = userAgent,
        )
        val id = db.playlists().insert(playlist)
        return loadNew(id)
    }

    suspend fun addXtream(
        name: String,
        portalUrl: String,
        username: String,
        password: String,
        userAgent: String?,
    ): RefreshSummary {
        // Verify before persisting, so a typo surfaces as an error on the setup
        // screen rather than as an empty playlist later.
        val client = XtreamClient(context, portalUrl, username, password, userAgent)
        client.account() // throws on bad credentials or an expired account

        val playlist = PlaylistEntity(
            name = name.ifBlank { Uri.parse(XtreamClient.normalizeBase(portalUrl)).host ?: "Xtream" },
            kind = SourceKind.XTREAM,
            source = XtreamClient.normalizeBase(portalUrl),
            username = username,
            password = password,
            epgUrls = client.xmltvUrl(),
            userAgent = userAgent,
        )
        val id = db.playlists().insert(playlist)
        return loadNew(id)
    }

    /**
     * The playlist row is kept even when its first load fails, so a provider
     * that is briefly down does not mean re-typing the login; the error still
     * surfaces so the setup screen can say what went wrong.
     */
    private suspend fun loadNew(playlistId: Long): RefreshSummary =
        refresh(playlistId).getOrElse { error ->
            throw IOException("Saved, but loading the channels failed: ${error.message}", error)
        }

    suspend fun delete(playlistId: Long) = db.playlists().delete(playlistId)

    suspend fun rename(playlistId: Long, name: String) {
        val existing = db.playlists().byId(playlistId) ?: return
        db.playlists().update(existing.copy(name = name))
    }

    /**
     * Re-downloads a playlist and swaps in its channels, movies and series.
     *
     * Live channels are the part that must work, so their failure fails the
     * refresh; the movie and series catalogues are best-effort, since some
     * providers disable those endpoints entirely.
     */
    suspend fun refresh(playlistId: Long): Result<RefreshSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val playlist = db.playlists().byId(playlistId)
                ?: throw IOException("Playlist $playlistId no longer exists")

            var movies: List<ParsedMovie> = emptyList()
            var shows: List<ParsedSeries> = emptyList()
            var m3uEpisodes: Map<String, List<ParsedEpisode>> = emptyMap()

            val parsed: ParsedPlaylist = when (playlist.kind) {
                SourceKind.XTREAM -> {
                    val client = XtreamClient(
                        context = context,
                        baseUrl = playlist.source,
                        username = playlist.username.orEmpty(),
                        password = playlist.password.orEmpty(),
                        userAgent = playlist.userAgent,
                    )
                    val live = client.livePlaylist()
                    movies = runCatching { client.movies() }
                        .onFailure { Log.w(TAG, "Movie list failed", it) }.getOrDefault(emptyList())
                    shows = runCatching { client.series() }
                        .onFailure { Log.w(TAG, "Series list failed", it) }.getOrDefault(emptyList())
                    live
                }

                SourceKind.M3U_URL -> Http.stream(context, playlist.source, playlist.userAgent) {
                    M3uParser.parse(it)
                }

                SourceKind.M3U_FILE -> context.contentResolver
                    .openInputStream(Uri.parse(playlist.source))
                    ?.use { M3uParser.parse(it) }
                    ?: throw IOException("Cannot open ${playlist.source}")
            }

            // M3U mixes everything in one list; sort it out here.
            val liveChannels = if (playlist.kind == SourceKind.XTREAM) parsed.channels else {
                val split = splitM3u(parsed.channels)
                movies = split.movies
                shows = split.shows
                m3uEpisodes = split.episodes
                split.live
            }

            if (liveChannels.isEmpty() && movies.isEmpty() && shows.isEmpty()) {
                throw IOException("Playlist contained no channels")
            }

            val entities = liveChannels.mapIndexed { index, channel ->
                ChannelEntity(
                    playlistId = playlistId,
                    name = channel.name,
                    url = channel.url,
                    tvgId = channel.tvgId,
                    logo = channel.logo,
                    groupName = channel.group,
                    number = channel.tvgChno ?: (index + 1),
                    ordinal = index,
                    catchupType = channel.catchupType,
                    catchupDays = channel.catchupDays,
                    catchupSource = channel.catchupSource,
                    userAgent = channel.userAgent ?: playlist.userAgent,
                    referrer = channel.referrer,
                )
            }
                // A provider repeating the same URL twice would otherwise trip the
                // unique index and abort the whole insert.
                .distinctBy { it.url }
            db.channels().replaceForPlaylist(playlistId, entities)

            db.movies().replaceForPlaylist(
                playlistId,
                movies.distinctBy { it.url }.mapIndexed { index, m ->
                    MovieEntity(
                        playlistId = playlistId,
                        streamId = m.streamId,
                        name = m.name,
                        url = m.url,
                        poster = m.poster,
                        category = m.category,
                        rating = m.rating,
                        year = m.year,
                        addedMs = m.addedMs,
                        ordinal = index,
                    )
                },
            )

            // Series rows are replaced wholesale; their episodes go with them by
            // cascade and are re-fetched when a show is next opened.
            db.series().deleteForPlaylist(playlistId)
            val seriesRows = shows.distinctBy { it.sourceKey }.mapIndexed { index, s ->
                SeriesEntity(
                    playlistId = playlistId,
                    sourceKey = s.sourceKey,
                    name = s.name,
                    cover = s.cover,
                    category = s.category,
                    rating = s.rating,
                    year = s.year,
                    plot = s.plot,
                    addedMs = s.addedMs,
                    ordinal = index,
                    episodesLoaded = s.sourceKey in m3uEpisodes,
                )
            }
            seriesRows.chunked(500).forEach { chunk ->
                val ids = db.series().insertAll(chunk)
                chunk.zip(ids).forEach { (row, id) ->
                    m3uEpisodes[row.sourceKey]?.let { eps ->
                        db.episodes().insertAll(eps.distinctBy { it.url }.map { it.toEntity(id) })
                    }
                }
            }

            // Remember any EPG url the playlist advertised, unless the user set one.
            if (playlist.epgUrls.isBlank() && parsed.epgUrls.isNotEmpty()) {
                db.playlists().update(playlist.copy(epgUrls = parsed.epgUrls.joinToString("\n")))
            }
            db.playlists().markSynced(playlistId, System.currentTimeMillis())
            RefreshSummary(entities.size, movies.size, seriesRows.size)
        }
    }

    private class M3uSplit(
        val live: List<ParsedChannel>,
        val movies: List<ParsedMovie>,
        val shows: List<ParsedSeries>,
        val episodes: Map<String, List<ParsedEpisode>>,
    )

    private fun splitM3u(entries: List<ParsedChannel>): M3uSplit {
        val live = ArrayList<ParsedChannel>()
        val movies = ArrayList<ParsedMovie>()
        val shows = LinkedHashMap<String, ParsedSeries>()
        val episodes = HashMap<String, MutableList<ParsedEpisode>>()

        for (entry in entries) {
            when (VodNames.classify(entry.url, entry.name)) {
                VodNames.Kind.LIVE -> live += entry
                VodNames.Kind.MOVIE -> movies += ParsedMovie(
                    name = VodNames.cleanTitle(entry.name).ifBlank { entry.name },
                    url = entry.url,
                    streamId = null,
                    poster = entry.logo,
                    category = entry.group,
                    rating = null,
                    year = VodNames.yearIn(entry.name),
                    addedMs = 0,
                )
                VodNames.Kind.EPISODE -> {
                    val ref = VodNames.parseEpisode(entry.name)
                    // No SxxEyy marker: file it under its group as one long season.
                    val show = ref?.show ?: entry.group ?: VodNames.cleanTitle(entry.name)
                    val key = VodNames.showKey(show)
                    shows.getOrPut(key) {
                        ParsedSeries(
                            sourceKey = key,
                            name = show,
                            cover = entry.logo,
                            category = entry.group,
                            rating = null,
                            year = VodNames.yearIn(entry.name),
                            plot = null,
                            addedMs = 0,
                        )
                    }
                    val list = episodes.getOrPut(key) { mutableListOf() }
                    list += ParsedEpisode(
                        season = ref?.season ?: 1,
                        episode = ref?.episode ?: (list.size + 1),
                        title = entry.name,
                        url = entry.url,
                        still = entry.logo,
                        plot = null,
                        durationSec = null,
                    )
                }
            }
        }
        return M3uSplit(live, movies, shows.values.toList(), episodes)
    }

    suspend fun refreshAll(): Map<Long, Result<RefreshSummary>> =
        db.playlists().all().associate { it.id to refresh(it.id) }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}

internal fun ParsedEpisode.toEntity(seriesId: Long) = EpisodeEntity(
    seriesId = seriesId,
    season = season,
    episode = episode,
    title = title,
    url = url,
    still = still,
    plot = plot,
    durationSec = durationSec,
)
