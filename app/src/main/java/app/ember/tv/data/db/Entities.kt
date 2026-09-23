package app.ember.tv.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ember.tv.data.model.CatchupType
import app.ember.tv.data.model.SourceKind

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    /** M3U url, local file uri, or the Xtream portal base url. */
    val source: String,
    val username: String? = null,
    val password: String? = null,
    /** Extra XMLTV urls the user added by hand, newline separated. */
    val epgUrls: String = "",
    val userAgent: String? = null,
    val lastSyncMs: Long = 0,
    val lastEpgSyncMs: Long = 0,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("playlistId"),
        Index("tvgId"),
        Index(value = ["playlistId", "url"], unique = true),
        Index("groupName"),
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val url: String,
    val tvgId: String?,
    val logo: String?,
    val groupName: String?,
    /** Provider-supplied channel number (tvg-chno), else the playlist ordinal. */
    val number: Int,
    val ordinal: Int,
    val catchupType: CatchupType = CatchupType.NONE,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val hidden: Boolean = false,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: Long,
    val addedMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "programmes",
    indices = [
        // Unique so a re-imported EPG replaces rows instead of duplicating them.
        Index(value = ["tvgId", "startMs"], unique = true),
        Index("stopMs"),
    ]
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tvgId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val icon: String?,
    val episodeNum: String?,
)

/** Last-watched bookkeeping, used for "recent channels" and resume-on-launch. */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val channelId: Long,
    val lastWatchedMs: Long,
    val watchCount: Int,
)

@Entity(
    tableName = "movies",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("playlistId"),
        Index("category"),
        Index("addedMs"),
        Index(value = ["playlistId", "url"], unique = true),
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val streamId: String?,
    val name: String,
    val url: String,
    val poster: String?,
    val category: String?,
    val rating: Double?,
    val year: Int?,
    val addedMs: Long,
    val ordinal: Int,
)

@Entity(
    tableName = "series",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("playlistId"),
        Index("category"),
        Index("addedMs"),
        Index(value = ["playlistId", "sourceKey"], unique = true),
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val sourceKey: String,
    val name: String,
    val cover: String?,
    val category: String?,
    val rating: Double?,
    val year: Int?,
    val plot: String?,
    val addedMs: Long,
    val ordinal: Int,
    /** Xtream lists episodes separately; they are fetched when a show is opened. */
    val episodesLoaded: Boolean = false,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = SeriesEntity::class,
        parentColumns = ["id"],
        childColumns = ["seriesId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("seriesId"),
        Index(value = ["seriesId", "url"], unique = true),
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val still: String?,
    val plot: String?,
    val durationSec: Int?,
)

/**
 * Where the viewer stopped in a movie or episode. Keyed by playlist and URL
 * rather than row id, because a playlist refresh re-creates the rows.
 */
@Entity(tableName = "progress", indices = [Index("updatedMs")])
data class ProgressEntity(
    @PrimaryKey val key: String,
    val playlistId: Long,
    /** "movie" or "episode". */
    val kind: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
    /** For episodes: the local series row, so "continue" can reopen the show. */
    val seriesId: Long? = null,
)
