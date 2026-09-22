package com.fincast.tv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A channel joined with its currently-airing programme, which is what every list row needs. */
data class ChannelWithNow(
    @Embedded val channel: ChannelEntity,
    val isFavorite: Boolean,
    val nowTitle: String?,
    val nowStartMs: Long?,
    val nowStopMs: Long?,
    val nextTitle: String?,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET lastSyncMs = :ms WHERE id = :id")
    suspend fun markSynced(id: Long, ms: Long)

    @Query("UPDATE playlists SET lastEpgSyncMs = :ms WHERE id = :id")
    suspend fun markEpgSynced(id: Long, ms: Long)
}

@Dao
interface ChannelDao {
    @Query(
        "SELECT DISTINCT groupName FROM channels " +
            "WHERE playlistId = :playlistId AND hidden = 0 AND groupName IS NOT NULL " +
            "ORDER BY groupName COLLATE NOCASE"
    )
    fun observeGroups(playlistId: Long): Flow<List<String>>

    /**
     * The channel list with now/next folded in. Doing the EPG lookup as correlated
     * subqueries keeps it one round trip — a 20k-channel playlist redraws without
     * a second query per row.
     */
    @Transaction
    @Query(
        """
        SELECT c.*,
               (f.channelId IS NOT NULL) AS isFavorite,
               (SELECT p.title   FROM programmes p WHERE p.tvgId = c.tvgId AND p.startMs <= :now AND p.stopMs > :now LIMIT 1) AS nowTitle,
               (SELECT p.startMs FROM programmes p WHERE p.tvgId = c.tvgId AND p.startMs <= :now AND p.stopMs > :now LIMIT 1) AS nowStartMs,
               (SELECT p.stopMs  FROM programmes p WHERE p.tvgId = c.tvgId AND p.startMs <= :now AND p.stopMs > :now LIMIT 1) AS nowStopMs,
               (SELECT p.title   FROM programmes p WHERE p.tvgId = c.tvgId AND p.startMs > :now ORDER BY p.startMs LIMIT 1) AS nextTitle
        FROM channels c
        LEFT JOIN favorites f ON f.channelId = c.id
        WHERE c.playlistId = :playlistId
          AND c.hidden = 0
          AND (:group IS NULL OR c.groupName = :group)
          AND (:favoritesOnly = 0 OR f.channelId IS NOT NULL)
          AND (:likeQuery IS NULL OR c.name LIKE :likeQuery)
        ORDER BY c.ordinal
        """
    )
    fun observeChannels(
        playlistId: Long,
        group: String?,
        favoritesOnly: Boolean,
        /** Null means "no search filter"; otherwise a pre-wrapped `%term%`. */
        likeQuery: String?,
        now: Long,
    ): Flow<List<ChannelWithNow>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND hidden = 0 ORDER BY ordinal")
    suspend fun channelsFor(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND number = :number AND hidden = 0 LIMIT 1")
    suspend fun byNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query("SELECT DISTINCT tvgId FROM channels WHERE tvgId IS NOT NULL AND LENGTH(tvgId) > 0")
    suspend fun knownTvgIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /**
     * Replaces a playlist's channels in one transaction, so a refresh that dies
     * halfway never leaves the user staring at an empty channel list.
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: Long, channels: List<ChannelEntity>) {
        deleteForPlaylist(playlistId)
        channels.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    suspend fun remove(channelId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: Long): Boolean

    @Transaction
    suspend fun toggle(channelId: Long) {
        if (isFavorite(channelId)) remove(channelId) else add(FavoriteEntity(channelId))
    }
}

@Dao
interface ProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("SELECT * FROM programmes WHERE tvgId = :tvgId AND stopMs > :from AND startMs < :to ORDER BY startMs")
    suspend fun inWindow(tvgId: String, from: Long, to: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE tvgId IN (:tvgIds) AND stopMs > :from AND startMs < :to ORDER BY tvgId, startMs")
    suspend fun inWindowFor(tvgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE tvgId = :tvgId AND startMs <= :now AND stopMs > :now LIMIT 1")
    suspend fun nowOn(tvgId: String, now: Long): ProgrammeEntity?

    @Query("DELETE FROM programmes WHERE stopMs < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("DELETE FROM programmes WHERE tvgId IN (:tvgIds)")
    suspend fun deleteFor(tvgIds: List<String>)

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int
}

@Dao
interface WatchStateDao {
    @Upsert
    suspend fun upsert(state: WatchStateEntity)

    @Query("SELECT * FROM watch_state WHERE channelId = :id")
    suspend fun byId(id: Long): WatchStateEntity?

    @Query("SELECT * FROM watch_state ORDER BY lastWatchedMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchStateEntity>

    @Transaction
    suspend fun record(channelId: Long) {
        val existing = byId(channelId)
        upsert(
            WatchStateEntity(
                channelId = channelId,
                lastWatchedMs = System.currentTimeMillis(),
                watchCount = (existing?.watchCount ?: 0) + 1,
            )
        )
    }
}
