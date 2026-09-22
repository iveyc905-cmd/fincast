package com.fincast.tv.data.model

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
