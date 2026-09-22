package com.fincast.tv.data.parse

import com.fincast.tv.data.model.CatchupType
import com.fincast.tv.data.model.ParsedChannel
import com.fincast.tv.data.model.ParsedPlaylist
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parser for extended M3U playlists.
 *
 * Providers are wildly inconsistent about this format, so the parser is
 * deliberately forgiving: unknown directives are skipped, attribute quoting is
 * optional, and a malformed entry drops that one channel rather than the file.
 *
 * Directives understood:
 *   #EXTM3U url-tvg="..." x-tvg-url="..."
 *   #EXTINF:<dur> key="value" ...,<display name>
 *   #EXTGRP:<group>            (alternative to group-title)
 *   #EXTVLCOPT:http-user-agent=..., http-referrer=...
 *   #KODIPROP:inputstream.adaptive.license_key=...  (kept as a header hint)
 *   #EXTHTTP:{"User-Agent":"...","Referer":"..."}
 */
object M3uParser {

    private val ATTR = Regex("""([A-Za-z0-9_-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s,]+))""")

    fun parse(input: InputStream): ParsedPlaylist =
        parse(BufferedReader(InputStreamReader(input, Charsets.UTF_8)))

    fun parse(text: String): ParsedPlaylist = parse(text.reader().buffered())

    fun parse(reader: BufferedReader): ParsedPlaylist {
        val channels = ArrayList<ParsedChannel>(4096)
        val epgUrls = LinkedHashSet<String>()

        var pending: Pending? = null

        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.let { epgUrls.addAll(it) }
                    }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val group = line.substringAfter(':', "").trim()
                    if (group.isNotEmpty()) pending = pending?.copy(group = group)
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter(':', "")
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        pending = when (key) {
                            "http-user-agent" -> pending?.copy(userAgent = value)
                            "http-referrer", "http-referer" -> pending?.copy(referrer = value)
                            else -> pending
                        }
                    }
                }

                line.startsWith("#EXTHTTP", ignoreCase = true) -> {
                    val headers = parseLooseJsonObject(line.substringAfter(':', ""))
                    if (headers.isNotEmpty()) {
                        pending = pending?.let { p ->
                            p.copy(
                                headers = p.headers + headers,
                                userAgent = headers.entryIgnoreCase("user-agent") ?: p.userAgent,
                                referrer = headers.entryIgnoreCase("referer")
                                    ?: headers.entryIgnoreCase("referrer") ?: p.referrer,
                            )
                        }
                    }
                }

                // Any other comment/directive we do not model yet.
                line.startsWith("#") -> Unit

                else -> {
                    val p = pending
                    if (p != null) {
                        channels += p.toChannel(line)
                        pending = null
                    }
                    // A bare URL with no preceding #EXTINF is not usable as a channel;
                    // without a name it cannot be shown or matched to EPG.
                }
            }
        }

        return ParsedPlaylist(channels = channels, epgUrls = epgUrls.toList())
    }

    private data class Pending(
        val name: String,
        val attrs: Map<String, String>,
        val group: String?,
        val userAgent: String?,
        val referrer: String?,
        val headers: Map<String, String>,
    ) {
        fun toChannel(url: String): ParsedChannel {
            val (type, source, days) = catchupOf(attrs)
            return ParsedChannel(
                name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: name,
                url = url,
                tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
                tvgName = attrs["tvg-name"],
                logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.takeIf { it.isNotEmpty() },
                group = group ?: attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() },
                tvgChno = (attrs["tvg-chno"] ?: attrs["channel-number"])
                    ?.trim()?.substringBefore('.')?.toIntOrNull(),
                catchupType = type,
                catchupDays = days,
                catchupSource = source,
                userAgent = userAgent,
                referrer = referrer,
                httpHeaders = headers,
            )
        }
    }

    private fun parseExtInf(line: String): Pending {
        // #EXTINF:-1 tvg-id="x" group-title="y",Channel Name
        val payload = line.substringAfter(':', "")
        val commaAt = payload.lastIndexOf(',')
        // Guard against a comma that lives inside an attribute value rather than
        // separating the display name.
        val safeComma = if (commaAt > 0 && payload.substring(commaAt).count { it == '"' } % 2 == 0) {
            commaAt
        } else {
            payload.indexOf(',').takeIf { it >= 0 } ?: payload.length
        }
        val head = payload.substring(0, safeComma)
        val name = payload.substring(minOf(safeComma + 1, payload.length)).trim()
        val attrs = attributes(head)
        return Pending(
            name = name.ifEmpty { attrs["tvg-name"] ?: "Unnamed" },
            attrs = attrs,
            group = null,
            userAgent = attrs["user-agent"],
            referrer = null,
            headers = emptyMap(),
        )
    }

    private fun attributes(line: String): Map<String, String> =
        ATTR.findAll(line).associate { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            key to value
        }

    /** Returns (type, source template, days). */
    private fun catchupOf(attrs: Map<String, String>): Triple<CatchupType, String?, Int> {
        val source = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
        val days = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])
            ?.trim()?.toIntOrNull() ?: 0

        val declared = attrs["catchup"]?.trim()?.lowercase()
        val type = when {
            declared == "append" -> CatchupType.APPEND
            declared == "shift" || declared == "timeshift" -> CatchupType.SHIFT
            declared == "flussonic" || declared == "flussonic-hls" -> CatchupType.FLUSSONIC
            declared == "xc" || declared == "xtream" -> CatchupType.XTREAM_CODES
            declared == "default" || declared == "1" -> {
                // "default" with a template means substitution; without one it is a
                // plain ?utc= shift.
                if (source != null) CatchupType.DEFAULT else CatchupType.SHIFT
            }
            source != null -> CatchupType.DEFAULT
            days > 0 -> CatchupType.SHIFT
            else -> CatchupType.NONE
        }
        return Triple(type, source, if (days == 0 && type != CatchupType.NONE) 7 else days)
    }

    /** Minimal object reader for `#EXTHTTP:{"k":"v", ...}` — not a general JSON parser. */
    private fun parseLooseJsonObject(text: String): Map<String, String> {
        val body = text.trim().removePrefix("{").removeSuffix("}")
        if (body.isBlank()) return emptyMap()
        return Regex("""\"([^\"]+)\"\s*:\s*\"([^\"]*)\"""")
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun Map<String, String>.entryIgnoreCase(key: String): String? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}
