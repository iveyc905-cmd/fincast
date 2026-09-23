package app.ember.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query(
        "SELECT DISTINCT category FROM movies WHERE playlistId = :playlistId AND category IS NOT NULL " +
            "ORDER BY category COLLATE NOCASE"
    )
    fun observeCategories(playlistId: Long): Flow<List<String>>

    @Query(
        "SELECT * FROM movies WHERE playlistId = :playlistId " +
            "AND (:category IS NULL OR category = :category) " +
            "AND (:likeQuery IS NULL OR name LIKE :likeQuery) " +
            "ORDER BY ordinal"
    )
    fun observe(playlistId: Long, category: String?, likeQuery: String?): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId ORDER BY addedMs DESC, ordinal LIMIT :limit")
    fun observeLatest(playlistId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun byId(id: Long): MovieEntity?

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Transaction
    suspend fun replaceForPlaylist(playlistId: Long, movies: List<MovieEntity>) {
        deleteForPlaylist(playlistId)
        movies.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface SeriesDao {
    @Query(
        "SELECT DISTINCT category FROM series WHERE playlistId = :playlistId AND category IS NOT NULL " +
            "ORDER BY category COLLATE NOCASE"
    )
    fun observeCategories(playlistId: Long): Flow<List<String>>

    @Query(
        "SELECT * FROM series WHERE playlistId = :playlistId " +
            "AND (:category IS NULL OR category = :category) " +
            "AND (:likeQuery IS NULL OR name LIKE :likeQuery) " +
            "ORDER BY ordinal"
    )
    fun observe(playlistId: Long, category: String?, likeQuery: String?): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId ORDER BY addedMs DESC, ordinal LIMIT :limit")
    fun observeLatest(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE id = :id")
    fun observeById(id: Long): Flow<SeriesEntity?>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Query("UPDATE series SET episodesLoaded = 1, plot = COALESCE(:plot, plot), cover = COALESCE(:cover, cover) WHERE id = :id")
    suspend fun markEpisodesLoaded(id: Long, plot: String?, cover: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>): List<Long>

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode, id")
    fun observeForSeries(seriesId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode, id")
    suspend fun forSeries(seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun deleteForSeries(seriesId: Long)

    @Transaction
    suspend fun replaceForSeries(seriesId: Long, episodes: List<EpisodeEntity>) {
        deleteForSeries(seriesId)
        episodes.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface ProgressDao {
    /**
     * Things started but not finished: past the first minute and short of the
     * credits. One entry per show, so a binge does not fill the row with
     * episodes of the same series.
     */
    @Query(
        """
        SELECT * FROM progress
        WHERE playlistId = :playlistId
          AND positionMs > 60000
          AND (durationMs <= 0 OR positionMs < durationMs * 0.95)
          AND updatedMs = (
              SELECT MAX(p2.updatedMs) FROM progress p2
              WHERE p2.playlistId = progress.playlistId
                AND COALESCE(p2.seriesId, -1) = COALESCE(progress.seriesId, -1)
                AND (progress.seriesId IS NOT NULL OR p2.key = progress.key)
          )
        ORDER BY updatedMs DESC
        LIMIT :limit
        """
    )
    fun observeContinue(playlistId: Long, limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE key = :key")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE key IN (:keys)")
    fun observeKeys(keys: List<String>): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE key = :key")
    suspend fun delete(key: String)
}
