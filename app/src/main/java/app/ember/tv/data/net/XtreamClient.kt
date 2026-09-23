package app.ember.tv.data.net

import android.content.Context
import android.net.Uri
import app.ember.tv.data.model.CatchupType
import app.ember.tv.data.model.MovieInfo
import app.ember.tv.data.model.ParsedEpisode
import app.ember.tv.data.model.ParsedMovie
import app.ember.tv.data.model.ParsedSeries
import app.ember.tv.data.parse.VodNames.yearIn
import app.ember.tv.data.model.ParsedChannel
import app.ember.tv.data.model.ParsedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class XtreamAccount(
    val status: String?,
    val expiresAtMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
)

/**
 * Client for the Xtream Codes player API, which most IPTV panels expose.
 *
 * Using it instead of the provider's `get.php` M3U export is worth the extra
 * code: categories arrive as real ids, streams carry numeric ids usable for
 * catch-up, and the account's expiry and connection limit become visible.
 */
class XtreamClient(
    private val context: Context,
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val userAgent: String? = null,
) {
    /** Normalised to `scheme://host[:port]` with no trailing slash or path. */
    private val base: String = normalizeBase(baseUrl)

    private fun api(action: String?, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base)
            .append("/player_api.php?username=").append(enc(username))
            .append("&password=").append(enc(password))
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return sb.toString()
    }

    suspend fun account(): XtreamAccount = withContext(Dispatchers.IO) {
        val root = JSONObject(Http.text(context, api(null), userAgent))
        val info = root.optJSONObject("user_info") ?: throw IOException("Login rejected by server")
        val status = info.optString("status").takeIf { it.isNotBlank() }
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Account status: $status")
        }
        XtreamAccount(
            status = status,
            expiresAtMs = info.optString("exp_date").toLongOrNull()?.times(1000L),
            maxConnections = info.optString("max_connections").toIntOrNull(),
            activeConnections = info.optString("active_cons").toIntOrNull(),
            isTrial = info.optString("is_trial") == "1",
        )
    }

    /** Live categories as id -> name. */
    private suspend fun liveCategories(): Map<String, String> = withContext(Dispatchers.IO) {
        val array = JSONArray(Http.text(context, api("get_live_categories"), userAgent))
        buildMap {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                put(o.optString("category_id"), o.optString("category_name"))
            }
        }
    }

    /**
     * Fetches every live stream and shapes it like a parsed M3U entry, so the
     * rest of the app does not care which source a channel came from.
     */
    suspend fun livePlaylist(containerExtension: String = "ts"): ParsedPlaylist =
        withContext(Dispatchers.IO) {
            val categories = runCatching { liveCategories() }.getOrDefault(emptyMap())
            val array = JSONArray(Http.text(context, api("get_live_streams"), userAgent))
            val channels = ArrayList<ParsedChannel>(array.length())

            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val streamId = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").ifBlank { "Channel $streamId" }
                val archiveDays = o.optString("tv_archive_duration").toIntOrNull() ?: 0
                val hasArchive = o.optString("tv_archive") == "1" || archiveDays > 0

                channels += ParsedChannel(
                    name = name,
                    url = "$base/live/${enc(username)}/${enc(password)}/$streamId.$containerExtension",
                    tvgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                    tvgName = name,
                    logo = o.optString("stream_icon").takeIf { it.isNotBlank() && it != "null" },
                    group = categories[o.optString("category_id")] ?: "Uncategorised",
                    tvgChno = o.optString("num").toIntOrNull(),
                    catchupType = if (hasArchive) CatchupType.XTREAM_CODES else CatchupType.NONE,
                    catchupDays = archiveDays,
                    // The timeshift endpoint needs the numeric stream id, not the URL.
                    catchupSource = if (hasArchive) streamId else null,
                    userAgent = userAgent,
                    referrer = null,
                )
            }

            ParsedPlaylist(channels = channels, epgUrls = listOf(xmltvUrl()))
        }

    // --- Video on demand ---------------------------------------------------

    private suspend fun categories(action: String): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(Http.text(context, api(action), userAgent))
            buildMap {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    put(o.optString("category_id"), o.optString("category_name"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun movies(): List<ParsedMovie> = withContext(Dispatchers.IO) {
        val categories = categories("get_vod_categories")
        val array = JSONArray(Http.text(context, api("get_vod_streams"), userAgent))
        val movies = ArrayList<ParsedMovie>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val ext = o.cleanString("container_extension") ?: "mp4"
            val name = o.cleanString("name") ?: continue
            movies += ParsedMovie(
                name = name,
                url = "$base/movie/${enc(username)}/${enc(password)}/$id.$ext",
                streamId = id,
                poster = o.cleanString("stream_icon"),
                category = categories[o.optString("category_id")],
                rating = o.cleanString("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                year = o.cleanString("year")?.take(4)?.toIntOrNull() ?: yearIn(name),
                addedMs = (o.cleanString("added")?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
        movies
    }

    suspend fun series(): List<ParsedSeries> = withContext(Dispatchers.IO) {
        val categories = categories("get_series_categories")
        val array = JSONArray(Http.text(context, api("get_series"), userAgent))
        val shows = ArrayList<ParsedSeries>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.opt("series_id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val name = o.cleanString("name") ?: continue
            val released = o.cleanString("releaseDate") ?: o.cleanString("release_date")
            shows += ParsedSeries(
                sourceKey = id,
                name = name,
                cover = o.cleanString("cover"),
                category = categories[o.optString("category_id")],
                rating = o.cleanString("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                year = released?.take(4)?.toIntOrNull() ?: yearIn(name),
                plot = o.cleanString("plot"),
                addedMs = (o.cleanString("last_modified")?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
        shows
    }

    /** Episodes of one show, plus its plot and cover when the listing lacked them. */
    suspend fun seriesInfo(seriesId: String): Triple<List<ParsedEpisode>, String?, String?> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(Http.text(context, api("get_series_info", mapOf("series_id" to seriesId)), userAgent))
            val info = root.optJSONObject("info")
            val episodes = ArrayList<ParsedEpisode>()

            fun read(o: JSONObject, seasonHint: Int) {
                val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return
                val ext = o.cleanString("container_extension") ?: "mp4"
                val details = o.optJSONObject("info")
                episodes += ParsedEpisode(
                    season = o.cleanString("season")?.toIntOrNull() ?: seasonHint,
                    episode = o.cleanString("episode_num")?.toIntOrNull() ?: (episodes.size + 1),
                    title = o.cleanString("title") ?: "Episode",
                    url = "$base/series/${enc(username)}/${enc(password)}/$id.$ext",
                    still = details?.cleanString("movie_image"),
                    plot = details?.cleanString("plot"),
                    durationSec = details?.cleanString("duration_secs")?.toIntOrNull(),
                )
            }

            // Panels disagree on the shape: usually {"1":[...],"2":[...]}, sometimes [[...],[...]].
            when (val raw = root.opt("episodes")) {
                is JSONObject -> raw.keys().forEach { key ->
                    val season = raw.optJSONArray(key) ?: return@forEach
                    for (i in 0 until season.length()) season.optJSONObject(i)?.let { read(it, key.toIntOrNull() ?: 1) }
                }
                is JSONArray -> for (s in 0 until raw.length()) {
                    val season = raw.optJSONArray(s) ?: continue
                    for (i in 0 until season.length()) season.optJSONObject(i)?.let { read(it, s + 1) }
                }
            }
            Triple(
                episodes.sortedWith(compareBy({ it.season }, { it.episode })),
                info?.cleanString("plot"),
                info?.cleanString("cover"),
            )
        }

    suspend fun movieInfo(vodId: String): MovieInfo = withContext(Dispatchers.IO) {
        val root = JSONObject(Http.text(context, api("get_vod_info", mapOf("vod_id" to vodId)), userAgent))
        val info = root.optJSONObject("info") ?: JSONObject()
        val backdrop = when (val b = info.opt("backdrop_path")) {
            is JSONArray -> (0 until b.length()).firstNotNullOfOrNull { b.optString(it).takeIf(String::isNotBlank) }
            is String -> b.takeIf { it.isNotBlank() }
            else -> null
        }
        MovieInfo(
            plot = info.cleanString("plot") ?: info.cleanString("description"),
            genre = info.cleanString("genre"),
            cast = info.cleanString("cast") ?: info.cleanString("actors"),
            director = info.cleanString("director"),
            durationSec = info.cleanString("duration_secs")?.toIntOrNull(),
            backdrop = backdrop,
            year = info.cleanString("releasedate")?.take(4)?.toIntOrNull(),
            rating = info.cleanString("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
        )
    }

    fun xmltvUrl(): String =
        "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    /**
     * Builds a catch-up URL for the panel's timeshift endpoint.
     * @param startMs wall-clock start of the requested programme
     * @param durationMinutes programme length
     */
    fun timeshiftUrl(streamId: String, startMs: Long, durationMinutes: Int): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .format(java.util.Date(startMs))
        return "$base/streaming/timeshift.php?username=${enc(username)}&password=${enc(password)}" +
            "&stream=$streamId&start=$stamp&duration=$durationMinutes"
    }

    companion object {
        fun normalizeBase(raw: String): String {
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else "http://$raw"
            val uri = Uri.parse(withScheme.trim().trimEnd('/'))
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "${uri.scheme}://${uri.host}$port"
        }

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** Panels send "", "null" and null interchangeably for "no value". */
        private fun JSONObject.cleanString(key: String): String? =
            opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
