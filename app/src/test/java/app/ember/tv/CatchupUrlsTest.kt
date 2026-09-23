package app.ember.tv

import app.ember.tv.data.db.ChannelEntity
import app.ember.tv.data.model.CatchupType
import app.ember.tv.data.parse.XmltvParser
import app.ember.tv.util.CatchupUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlsTest {

    private val startMs = 1_700_000_000_000L      // 2023-11-14 22:13:20 UTC
    private val endMs = startMs + 3_600_000L
    private val startSec = startMs / 1000
    private val endSec = endMs / 1000

    private fun channel(
        url: String,
        type: CatchupType,
        source: String? = null,
        days: Int = 7,
    ) = ChannelEntity(
        id = 1,
        playlistId = 1,
        name = "Test",
        url = url,
        tvgId = "test.tv",
        logo = null,
        groupName = null,
        number = 1,
        ordinal = 0,
        catchupType = type,
        catchupDays = days,
        catchupSource = source,
    )

    @Test
    fun `shift appends utc and lutc`() {
        val url = CatchupUrls.build(
            channel("http://host/live/1.ts", CatchupType.SHIFT),
            startMs,
            endMs,
        )!!
        assertTrue(url.startsWith("http://host/live/1.ts?utc=$startSec&lutc="))
    }

    @Test
    fun `shift respects an existing query string`() {
        val url = CatchupUrls.build(
            channel("http://host/live/1.ts?token=abc", CatchupType.SHIFT),
            startMs,
            endMs,
        )!!
        assertTrue(url.contains("?token=abc&utc=$startSec"))
    }

    @Test
    fun `append substitutes tokens into the suffix`() {
        val url = CatchupUrls.build(
            channel(
                url = "http://host/live/1.ts",
                type = CatchupType.APPEND,
                source = "?utc={utc}&duration={duration}",
            ),
            startMs,
            endMs,
        )!!
        assertEquals("http://host/live/1.ts?utc=$startSec&duration=3600", url)
    }

    @Test
    fun `default template with an absolute url is used as-is`() {
        val url = CatchupUrls.build(
            channel(
                url = "http://host/live/1.ts",
                type = CatchupType.DEFAULT,
                source = "http://host/archive/1?from=\${start}&to=\${end}",
            ),
            startMs,
            endMs,
        )!!
        assertEquals("http://host/archive/1?from=$startSec&to=$endSec", url)
    }

    @Test
    fun `default template with a relative path resolves against the live url`() {
        val url = CatchupUrls.build(
            channel(
                url = "http://host/live/stream.m3u8",
                type = CatchupType.DEFAULT,
                source = "archive-{utc}-{duration}.m3u8",
            ),
            startMs,
            endMs,
        )!!
        assertEquals("http://host/live/archive-$startSec-3600.m3u8", url)
    }

    @Test
    fun `flussonic rewrites the playlist filename`() {
        assertEquals(
            "http://host/ch/archive-$startSec-3600.m3u8",
            CatchupUrls.build(
                channel("http://host/ch/index.m3u8", CatchupType.FLUSSONIC),
                startMs,
                endMs,
            ),
        )
        assertEquals(
            "http://host/ch/mono-$startSec-3600.m3u8",
            CatchupUrls.build(
                channel("http://host/ch/mono.m3u8", CatchupType.FLUSSONIC),
                startMs,
                endMs,
            ),
        )
    }

    @Test
    fun `no catch-up yields no url`() {
        assertNull(
            CatchupUrls.build(channel("http://host/live/1.ts", CatchupType.NONE), startMs, endMs)
        )
    }

    @Test
    fun `xmltv timestamps honour the offset`() {
        val utc = XmltvParser.parseXmltvTime("20240115143000 +0000")!!
        val plusOne = XmltvParser.parseXmltvTime("20240115143000 +0100")!!
        assertEquals(3_600_000L, utc - plusOne)
    }

    @Test
    fun `xmltv timestamps without an offset are treated as utc`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20240115143000 +0000"),
            XmltvParser.parseXmltvTime("20240115143000"),
        )
    }

    @Test
    fun `truncated xmltv dates still parse`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20240115000000 +0000"),
            XmltvParser.parseXmltvTime("20240115"),
        )
    }
}
