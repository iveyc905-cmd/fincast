package app.ember.tv.data.repo

import android.content.Context
import android.util.Log
import app.ember.tv.data.db.AppDatabase
import app.ember.tv.data.db.ProgrammeEntity
import app.ember.tv.data.net.Http
import app.ember.tv.data.parse.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class EpgRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
) {
    suspend fun now(tvgId: String?): ProgrammeEntity? {
        if (tvgId.isNullOrBlank()) return null
        return db.programmes().nowOn(tvgId, System.currentTimeMillis())
    }

    suspend fun schedule(tvgId: String?, fromMs: Long, toMs: Long): List<ProgrammeEntity> {
        if (tvgId.isNullOrBlank()) return emptyList()
        return db.programmes().inWindow(tvgId, fromMs, toMs)
    }

    /** Guide grid data: every requested channel's programmes in one query. */
    suspend fun grid(tvgIds: List<String>, fromMs: Long, toMs: Long): Map<String, List<ProgrammeEntity>> {
        if (tvgIds.isEmpty()) return emptyMap()
        return tvgIds.chunked(900) // stay under SQLite's variable limit
            .flatMap { db.programmes().inWindowFor(it, fromMs, toMs) }
            .groupBy { it.tvgId }
    }

    /**
     * Downloads and stores EPG for every playlist that has an XMLTV source.
     *
     * Programmes for channels the user does not have are dropped during parsing —
     * a shared XMLTV feed is often an order of magnitude larger than the
     * subscriber's own channel list.
     */
    suspend fun refreshAll(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val knownIds = db.channels().knownTvgIds().toSet()
            if (knownIds.isEmpty()) return@runCatching 0

            val settings = SettingsRepository(context).current()
            val staleBefore = System.currentTimeMillis() -
                TimeUnit.HOURS.toMillis(settings.epgRefreshHours.toLong())

            var imported = 0
            for (playlist in db.playlists().all()) {
                if (!force && playlist.lastEpgSyncMs > staleBefore) continue

                val urls = playlist.epgUrls.lines().map(String::trim).filter { it.isNotEmpty() }
                if (urls.isEmpty()) continue

                var playlistImported = 0
                for (url in urls) {
                    runCatching {
                        Http.stream(context, url, playlist.userAgent ?: settings.userAgent) { stream ->
                            XmltvParser.parse(stream, keepChannelIds = knownIds) { batch ->
                                // The parser is synchronous; this is the one place a
                                // blocking write is simpler than threading a channel through.
                                runBlocking {
                                    db.programmes().insertAll(
                                        batch.map {
                                            ProgrammeEntity(
                                                tvgId = it.channelId,
                                                startMs = it.startMs,
                                                stopMs = it.stopMs,
                                                title = it.title,
                                                description = it.description,
                                                category = it.category,
                                                icon = it.icon,
                                                episodeNum = it.episodeNum,
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }.onSuccess { count ->
                        playlistImported += count
                    }.onFailure { error ->
                        Log.w(TAG, "EPG source failed: $url", error)
                    }
                }

                if (playlistImported > 0) {
                    db.playlists().markEpgSynced(playlist.id, System.currentTimeMillis())
                    imported += playlistImported
                }
            }

            // Yesterday's listings are still wanted for catch-up browsing; anything
            // older is dead weight.
            db.programmes().pruneOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))
            imported
        }
    }

    private companion object {
        const val TAG = "EpgRepository"
    }
}
