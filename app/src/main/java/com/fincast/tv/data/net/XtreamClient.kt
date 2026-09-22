package com.fincast.tv.data.net

import android.content.Context
import android.net.Uri
import com.fincast.tv.data.model.CatchupType
import com.fincast.tv.data.model.ParsedChannel
import com.fincast.tv.data.model.ParsedPlaylist
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
    }
}
