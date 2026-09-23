package app.ember.tv.data.parse

/**
 * Heuristics for telling live channels, movies and episodes apart in an M3U
 * playlist, which has no field for it.
 *
 * The URL is the most reliable signal: Xtream-style panels serve films from
 * `/movie/` and episodes from `/series/`. A file extension comes next (nobody
 * serves a live channel as `.mkv`), and the title's `S01E02` marker decides
 * between a film and an episode.
 */
object VodNames {

    enum class Kind { LIVE, MOVIE, EPISODE }

    data class EpisodeRef(val show: String, val season: Int, val episode: Int)

    private val VIDEO_FILE = Regex("""\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv|mpg|mpeg)$""", RegexOption.IGNORE_CASE)
    private val EPISODE_MARK = Regex("""^(.*?)[\s._\-:|]*S(\d{1,2})\s*[._-]?\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR_IN_PARENS = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    // Needs a dash: a bare trailing number is too often part of the title
    // ("Blade Runner 2049").
    private val TRAILING_YEAR = Regex("""\s[-–]\s*((?:19|20)\d{2})\s*$""")

    fun classify(url: String, name: String): Kind {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/series/" in path -> Kind.EPISODE
            "/movie/" in path || "/movies/" in path -> Kind.MOVIE
            VIDEO_FILE.containsMatchIn(path) ->
                if (EPISODE_MARK.containsMatchIn(name)) Kind.EPISODE else Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    fun parseEpisode(name: String): EpisodeRef? {
        val m = EPISODE_MARK.find(name) ?: return null
        val show = cleanTitle(m.groupValues[1]).ifBlank { return null }
        return EpisodeRef(show, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun yearIn(name: String): Int? =
        (YEAR_IN_PARENS.find(name) ?: TRAILING_YEAR.find(name))
            ?.groupValues?.get(1)?.toIntOrNull()

    /** Drops the year and separator clutter providers bolt onto titles. */
    fun cleanTitle(name: String): String =
        name.replace(YEAR_IN_PARENS, "")
            .replace(TRAILING_YEAR, "")
            .trim(' ', '-', '_', '.', ':', '|')
            .replace(Regex("""\s{2,}"""), " ")

    /** Series grouping key: case and punctuation differences are the same show. */
    fun showKey(show: String): String =
        show.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
