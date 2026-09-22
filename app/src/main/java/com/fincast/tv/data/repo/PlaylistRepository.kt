package com.fincast.tv.data.repo

import android.content.Context
import android.net.Uri
import com.fincast.tv.data.db.AppDatabase
import com.fincast.tv.data.db.ChannelEntity
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.data.db.PlaylistEntity
import com.fincast.tv.data.model.ParsedPlaylist
import com.fincast.tv.data.model.SourceKind
import com.fincast.tv.data.net.Http
import com.fincast.tv.data.net.XtreamClient
import com.fincast.tv.data.parse.M3uParser
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

    /** @return the number of channels loaded; throws if the playlist could not be loaded. */
    suspend fun addM3uUrl(name: String, url: String, userAgent: String?): Int {
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
    ): Int {
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
    private suspend fun loadNew(playlistId: Long): Int =
        refresh(playlistId).getOrElse { error ->
            throw IOException("Saved, but loading the channels failed: ${error.message}", error)
        }

    suspend fun delete(playlistId: Long) = db.playlists().delete(playlistId)

    suspend fun rename(playlistId: Long, name: String) {
        val existing = db.playlists().byId(playlistId) ?: return
        db.playlists().update(existing.copy(name = name))
    }

    /** Re-downloads a playlist and swaps in the new channel list atomically. */
    suspend fun refresh(playlistId: Long): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val playlist = db.playlists().byId(playlistId)
                ?: throw IOException("Playlist $playlistId no longer exists")

            val parsed: ParsedPlaylist = when (playlist.kind) {
                SourceKind.XTREAM -> XtreamClient(
                    context = context,
                    baseUrl = playlist.source,
                    username = playlist.username.orEmpty(),
                    password = playlist.password.orEmpty(),
                    userAgent = playlist.userAgent,
                ).livePlaylist()

                SourceKind.M3U_URL -> Http.stream(context, playlist.source, playlist.userAgent) {
                    M3uParser.parse(it)
                }

                SourceKind.M3U_FILE -> context.contentResolver
                    .openInputStream(Uri.parse(playlist.source))
                    ?.use { M3uParser.parse(it) }
                    ?: throw IOException("Cannot open ${playlist.source}")
            }

            if (parsed.channels.isEmpty()) throw IOException("Playlist contained no channels")

            val entities = parsed.channels.mapIndexed { index, channel ->
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

            // Remember any EPG url the playlist advertised, unless the user set one.
            if (playlist.epgUrls.isBlank() && parsed.epgUrls.isNotEmpty()) {
                db.playlists().update(playlist.copy(epgUrls = parsed.epgUrls.joinToString("\n")))
            }
            db.playlists().markSynced(playlistId, System.currentTimeMillis())
            entities.size
        }
    }

    suspend fun refreshAll(): Map<Long, Result<Int>> =
        db.playlists().all().associate { it.id to refresh(it.id) }
}
