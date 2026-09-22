package com.opentv.tv.data.parse

import android.util.Xml
import com.opentv.tv.data.model.ParsedProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV reader.
 *
 * EPG files from large providers routinely run to hundreds of megabytes, so
 * nothing here builds a document tree or accumulates the full programme list —
 * results are handed to [onBatch] in chunks and the caller writes them straight
 * to the database.
 */
object XmltvParser {

    /** Programmes for channels outside this set are discarded during parsing. */
    fun parse(
        input: InputStream,
        keepChannelIds: Set<String>? = null,
        batchSize: Int = 2000,
        onBatch: (List<ParsedProgramme>) -> Unit,
    ): Int {
        val stream = maybeGunzip(BufferedInputStream(input, 64 * 1024))
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val batch = ArrayList<ParsedProgramme>(batchSize)
        var total = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val programme = readProgramme(parser)
                if (programme != null && (keepChannelIds == null || programme.channelId in keepChannelIds)) {
                    batch += programme
                    if (batch.size >= batchSize) {
                        onBatch(batch.toList())
                        total += batch.size
                        batch.clear()
                    }
                }
            }
            event = parser.next()
        }
        if (batch.isNotEmpty()) {
            onBatch(batch.toList())
            total += batch.size
        }
        return total
    }

    /** Reads `<channel>` elements only — used to show which ids an EPG source covers. */
    fun parseChannelIds(input: InputStream): Set<String> {
        val stream = maybeGunzip(BufferedInputStream(input, 64 * 1024))
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        val ids = LinkedHashSet<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> parser.getAttributeValue(null, "id")?.let { ids += it }
                    // Channel declarations always precede programmes in a valid file.
                    "programme" -> return ids
                }
            }
            event = parser.next()
        }
        return ids
    }

    private fun readProgramme(parser: XmlPullParser): ParsedProgramme? {
        val channelId = parser.getAttributeValue(null, "channel") ?: return null
        val start = parseXmltvTime(parser.getAttributeValue(null, "start")) ?: return null
        val stopAttr = parser.getAttributeValue(null, "stop")
        var stop = parseXmltvTime(stopAttr) ?: (start + 30 * 60_000L)

        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var icon: String? = null
        var episode: String? = null

        // Depth 1 == inside <programme>. Leaf readers consume their own END_TAG,
        // so they leave the depth where they found it.
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    // A programme may repeat these once per language; keep the first.
                    "title" -> textOf(parser).let { if (title == null && it.isNotEmpty()) title = it }
                    "desc" -> textOf(parser).let { if (desc == null && it.isNotEmpty()) desc = it }
                    "category" -> textOf(parser).let { if (category == null && it.isNotEmpty()) category = it }
                    "episode-num" -> textOf(parser).let { if (episode == null && it.isNotEmpty()) episode = it }
                    "icon" -> {
                        icon = icon ?: parser.getAttributeValue(null, "src")
                        depth++ // <icon/> is empty but still reports an END_TAG
                    }
                    else -> depth++
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null
            }
        }

        if (stop <= start) stop = start + 30 * 60_000L
        return ParsedProgramme(
            channelId = channelId,
            startMs = start,
            stopMs = stop,
            title = title?.takeIf { it.isNotBlank() } ?: "—",
            description = desc,
            category = category,
            icon = icon,
            episodeNum = episode,
        )
    }

    /** Consumes the current element and returns its text content. */
    private fun textOf(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return sb.toString().trim()
    }

    /**
     * XMLTV times look like `20240115143000 +0100`; the offset is optional and
     * the date part may be truncated to as little as `YYYYMMDD`.
     */
    fun parseXmltvTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        val digits = text.takeWhile { it.isDigit() }
        if (digits.length < 8) return null

        fun part(from: Int, len: Int, fallback: Int): Int =
            if (digits.length >= from + len) digits.substring(from, from + len).toIntOrNull() ?: fallback
            else fallback

        val offsetText = text.drop(digits.length).trim()
        val zone = when {
            offsetText.isEmpty() -> TimeZone.getTimeZone("UTC")
            offsetText.length >= 5 && (offsetText[0] == '+' || offsetText[0] == '-') ->
                TimeZone.getTimeZone("GMT$offsetText")
            else -> TimeZone.getTimeZone(offsetText)
        }

        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(
            part(0, 4, 1970),
            part(4, 2, 1) - 1,
            part(6, 2, 1),
            part(8, 2, 0),
            part(10, 2, 0),
            part(12, 2, 0),
        )
        return cal.timeInMillis
    }

    /** Sniffs the gzip magic number so a `.xml` URL that actually serves gzip still works. */
    private fun maybeGunzip(stream: BufferedInputStream): InputStream {
        stream.mark(2)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        val isGzip = b0 == 0x1f && b1 == 0x8b
        return if (isGzip) GZIPInputStream(stream, 64 * 1024) else stream
    }
}
