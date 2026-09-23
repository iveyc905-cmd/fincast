package app.ember.tv.util

import app.ember.tv.data.db.ChannelEntity
import app.ember.tv.data.model.CatchupType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a live channel plus a past time range into an archive (catch-up) URL.
 *
 * There is no standard here — every panel vendor invented its own scheme — so
 * this covers the five conventions that actually show up in the wild and falls
 * back to the live URL when a channel has no archive.
 */
object CatchupUrls {

    /**
     * @param startMs programme start, wall clock
     * @param endMs programme end, wall clock
     * @return an archive URL, or null when the channel has no usable archive
     */
    fun build(channel: ChannelEntity, startMs: Long, endMs: Long): String? {
        val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(60)
        val startSec = startMs / 1000
        val endSec = endMs / 1000
        val nowSec = System.currentTimeMillis() / 1000
        val offsetSec = (nowSec - startSec).coerceAtLeast(0)

        return when (channel.catchupType) {
            CatchupType.NONE -> null

            CatchupType.DEFAULT -> {
                val template = channel.catchupSource ?: return null
                substitute(template, startSec, endSec, durationSec, offsetSec)
                    .let { if (it.startsWith("http")) it else joinRelative(channel.url, it) }
            }

            CatchupType.APPEND -> {
                val suffix = substitute(channel.catchupSource ?: "", startSec, endSec, durationSec, offsetSec)
                channel.url + suffix
            }

            CatchupType.SHIFT -> {
                val separator = if (channel.url.contains('?')) "&" else "?"
                "${channel.url}${separator}utc=$startSec&lutc=$nowSec"
            }

            CatchupType.FLUSSONIC -> flussonic(channel.url, startSec, durationSec)

            // Handled by XtreamClient.timeshiftUrl, which needs the account
            // credentials this function does not have.
            CatchupType.XTREAM_CODES -> null
        }
    }

    fun substitute(
        template: String,
        startSec: Long,
        endSec: Long,
        durationSec: Long,
        offsetSec: Long,
    ): String {
        var out = template
        val replacements = mapOf(
            "utc" to startSec.toString(),
            "start" to startSec.toString(),
            "timestamp" to startSec.toString(),
            "utcend" to endSec.toString(),
            "end" to endSec.toString(),
            "lutc" to (System.currentTimeMillis() / 1000).toString(),
            "now" to (System.currentTimeMillis() / 1000).toString(),
            "duration" to durationSec.toString(),
            "durmin" to (durationSec / 60).toString(),
            "offset" to offsetSec.toString(),
            "b" to startSec.toString(),
            "e" to endSec.toString(),
        )
        replacements.forEach { (key, value) ->
            // Providers write these as {key}, ${key} or ${key:...}
            out = out.replace("\${$key}", value).replace("{$key}", value)
        }
        out = strftime(out, startSec * 1000, prefix = "")
        out = strftime(out, endSec * 1000, prefix = "end-")
        return out
    }

    /** Expands `{Y}{m}{d}{H}{M}{S}`-style date tokens against [timeMs]. */
    private fun strftime(template: String, timeMs: Long, prefix: String): String {
        if (!template.contains('{')) return template
        val tokens = mapOf(
            "Y" to "yyyy", "y" to "yy", "m" to "MM", "d" to "dd",
            "H" to "HH", "M" to "mm", "S" to "ss",
        )
        var out = template
        tokens.forEach { (token, pattern) ->
            val key = "$prefix$token"
            if (out.contains("{$key}") || out.contains("\${$key}")) {
                val formatter = SimpleDateFormat(pattern, Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("UTC")
                val value = formatter.format(Date(timeMs))
                out = out.replace("{$key}", value).replace("\${$key}", value)
            }
        }
        return out
    }

    /**
     * Flussonic archives replace the playlist filename:
     *   .../ch/index.m3u8 -> .../ch/archive-<start>-<duration>.m3u8
     *   .../ch/mono.m3u8  -> .../ch/mono-<start>-<duration>.m3u8
     */
    private fun flussonic(liveUrl: String, startSec: Long, durationSec: Long): String {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?')
        val dir = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')

        val replacement = when {
            file.startsWith("mono") -> "mono-$startSec-$durationSec.m3u8"
            file.endsWith(".ts") -> "archive-$startSec-$durationSec.ts"
            else -> "archive-$startSec-$durationSec.m3u8"
        }
        return "$dir/$replacement$query"
    }

    private fun joinRelative(liveUrl: String, relative: String): String {
        val root = liveUrl.substringBefore('?').substringBeforeLast('/')
        return if (relative.startsWith("/")) {
            val origin = Regex("^(https?://[^/]+)").find(liveUrl)?.value ?: root
            origin + relative
        } else {
            "$root/$relative"
        }
    }
}
