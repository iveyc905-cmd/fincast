package app.ember.tv.data.repo

import android.content.Context
import app.ember.tv.data.db.AppDatabase
import app.ember.tv.data.db.EpisodeEntity
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.ProgressEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.data.model.MovieInfo
import app.ember.tv.data.model.SourceKind
import app.ember.tv.data.net.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VodRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
) {
    private val episodeLoads = Mutex()
    private val movieInfoCache = ConcurrentHashMap<Long, MovieInfo>()

    fun movieCategories(playlistId: Long) = db.movies().observeCategories(playlistId)
    fun seriesCategories(playlistId: Long) = db.series().observeCategories(playlistId)

    fun movies(playlistId: Long, category: String?, query: String = ""): Flow<List<MovieEntity>> =
        db.movies().observe(playlistId, category, like(query))

    fun series(playlistId: Long, category: String?, query: String = ""): Flow<List<SeriesEntity>> =
        db.series().observe(playlistId, category, like(query))

    fun latestMovies(playlistId: Long, limit: Int = 20) = db.movies().observeLatest(playlistId, limit)
    fun latestSeries(playlistId: Long, limit: Int = 20) = db.series().observeLatest(playlistId, limit)
    fun movieCount(playlistId: Long) = db.movies().observeCount(playlistId)
    fun seriesCount(playlistId: Long) = db.series().observeCount(playlistId)

    suspend fun movie(id: Long) = db.movies().byId(id)
    suspend fun show(id: Long) = db.series().byId(id)
    fun observeShow(id: Long) = db.series().observeById(id)
    fun episodes(seriesId: Long) = db.episodes().observeForSeries(seriesId)
    suspend fun episodeList(seriesId: Long) = db.episodes().forSeries(seriesId)

    /**
     * Makes sure a show's episodes are in the database. Xtream only lists them
     * per show, so they are fetched the first time the show is opened.
     */
    suspend fun ensureEpisodes(seriesId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            episodeLoads.withLock {
                val show = db.series().byId(seriesId) ?: return@withLock
                if (show.episodesLoaded) return@withLock
                val client = xtreamFor(show.playlistId) ?: return@withLock
                val (episodes, plot, cover) = client.seriesInfo(show.sourceKey)
                db.episodes().replaceForSeries(seriesId, episodes.map { it.toEntity(seriesId) })
                db.series().markEpisodesLoaded(seriesId, plot, cover)
            }
        }
    }

    /** Plot, cast and backdrop for an Xtream movie; null for M3U films, which carry none. */
    suspend fun movieInfo(movie: MovieEntity): MovieInfo? {
        movieInfoCache[movie.id]?.let { return it }
        val id = movie.streamId ?: return null
        val client = xtreamFor(movie.playlistId) ?: return null
        return runCatching { client.movieInfo(id) }.getOrNull()?.also { movieInfoCache[movie.id] = it }
    }

    private suspend fun xtreamFor(playlistId: Long): XtreamClient? {
        val playlist = db.playlists().byId(playlistId) ?: return null
        if (playlist.kind != SourceKind.XTREAM) return null
        return XtreamClient(
            context = context,
            baseUrl = playlist.source,
            username = playlist.username.orEmpty(),
            password = playlist.password.orEmpty(),
            userAgent = playlist.userAgent,
        )
    }

    // --- Resume positions --------------------------------------------------

    fun continueWatching(playlistId: Long, limit: Int = 20) =
        db.progress().observeContinue(playlistId, limit)

    fun progressFor(keys: List<String>) = db.progress().observeKeys(keys)

    suspend fun progress(key: String) = db.progress().get(key)

    suspend fun saveProgress(entry: ProgressEntity) = db.progress().upsert(entry)

    suspend fun clearProgress(key: String) = db.progress().delete(key)

    companion object {
        fun movieKey(movie: MovieEntity) = "movie:${movie.playlistId}:${movie.url}"
        fun episodeKey(playlistId: Long, episode: EpisodeEntity) = "ep:$playlistId:${episode.url}"

        private fun like(query: String) = query.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" }
    }
}
