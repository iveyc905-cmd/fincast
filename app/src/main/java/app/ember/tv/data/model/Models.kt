package app.ember.tv.data.model

/** How a provider exposes archive/catch-up streams for a channel. */
enum class CatchupType { NONE, DEFAULT, APPEND, SHIFT, XTREAM_CODES, FLUSSONIC }

/** What kind of credentials a playlist was created from. */
enum class SourceKind { M3U_URL, M3U_FILE, XTREAM }

data class ParsedChannel(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val tvgChno: Int?,
    val catchupType: CatchupType,
    val catchupDays: Int,
    val catchupSource: String?,
    val userAgent: String?,
    val referrer: String?,
    val httpHeaders: Map<String, String> = emptyMap(),
)

data class ParsedPlaylist(
    val channels: List<ParsedChannel>,
    /** url-tvg / x-tvg-url from the #EXTM3U header, if the provider supplied one. */
    val epgUrls: List<String>,
)

data class ParsedProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val icon: String?,
    val episodeNum: String?,
)

// --- Video on demand -------------------------------------------------------

data class ParsedMovie(
    val name: String,
    val url: String,
    /** Xtream vod_id, used to fetch plot and cast on demand; null for M3U. */
    val streamId: String?,
    val poster: String?,
    val category: String?,
    val rating: Double?,
    val year: Int?,
    val addedMs: Long,
)

data class ParsedSeries(
    /** Xtream series_id, or for M3U the normalised show name. */
    val sourceKey: String,
    val name: String,
    val cover: String?,
    val category: String?,
    val rating: Double?,
    val year: Int?,
    val plot: String?,
    val addedMs: Long,
)

data class ParsedEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val still: String?,
    val plot: String?,
    val durationSec: Int?,
)

/** Extra detail for one movie, fetched when its page opens. */
data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val durationSec: Int?,
    val backdrop: String?,
    val year: Int?,
    val rating: Double?,
)

/** Everything one playlist refresh produced, for the setup screen to report. */
data class RefreshSummary(val channels: Int, val movies: Int, val series: Int) {
    fun describe(): String = buildList {
        add("$channels channels")
        if (movies > 0) add("$movies movies")
        if (series > 0) add("$series series")
    }.joinToString(", ", prefix = "Loaded ")
}
