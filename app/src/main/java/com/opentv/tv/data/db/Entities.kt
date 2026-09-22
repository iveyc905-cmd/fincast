package com.opentv.tv.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.opentv.tv.data.model.CatchupType
import com.opentv.tv.data.model.SourceKind

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
