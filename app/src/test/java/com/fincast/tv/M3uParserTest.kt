package com.fincast.tv

import com.fincast.tv.data.model.CatchupType
import com.fincast.tv.data.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `reads attributes and display name`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U url-tvg="http://epg.example/xmltv.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo/1.png" group-title="UK" tvg-chno="101",BBC One HD
            http://stream.example/live/1.ts
            """.trimIndent()
        )

        assertEquals(1, playlist.channels.size)
        assertEquals(listOf("http://epg.example/xmltv.xml.gz"), playlist.epgUrls)

        val channel = playlist.channels.single()
        assertEquals("BBC One", channel.name) // tvg-name wins over the display name
        assertEquals("bbc1.uk", channel.tvgId)
        assertEquals("UK", channel.group)
        assertEquals(101, channel.tvgChno)
        assertEquals("http://stream.example/live/1.ts", channel.url)
    }

    @Test
    fun `falls back to display name when tvg-name is absent`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport",Sky Sports Main Event
            http://stream.example/2
            """.trimIndent()
        )
        assertEquals("Sky Sports Main Event", playlist.channels.single().name)
    }

    @Test
    fun `handles a comma inside an attribute value`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" group-title="News, Talk",CNN International
            http://stream.example/3
            """.trimIndent()
        )
        val channel = playlist.channels.single()
        assertEquals("CNN International", channel.name)
        assertEquals("News, Talk", channel.group)
    }

    @Test
    fun `EXTGRP overrides group-title`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Ignored",Channel
            #EXTGRP:Movies
            http://stream.example/4
            """.trimIndent()
        )
        assertEquals("Movies", playlist.channels.single().group)
    }

    @Test
    fun `reads VLC user agent and referrer options`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTVLCOPT:http-user-agent=MyPlayer/2.0
            #EXTVLCOPT:http-referrer=http://portal.example/
            http://stream.example/5
            """.trimIndent()
        )
        val channel = playlist.channels.single()
        assertEquals("MyPlayer/2.0", channel.userAgent)
        assertEquals("http://portal.example/", channel.referrer)
    }

    @Test
    fun `reads EXTHTTP headers`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTHTTP:{"User-Agent":"Roku/9.0","Referer":"http://ref.example/"}
            http://stream.example/6
            """.trimIndent()
        )
        val channel = playlist.channels.single()
        assertEquals("Roku/9.0", channel.userAgent)
        assertEquals("http://ref.example/", channel.referrer)
        assertEquals(2, channel.httpHeaders.size)
    }

    @Test
    fun `detects catch-up variants`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="shift" catchup-days="7",Shift
            http://stream.example/a
            #EXTINF:-1 catchup="append" catchup-source="?utc={utc}&lutc={lutc}" catchup-days="3",Append
            http://stream.example/b
            #EXTINF:-1 catchup="flussonic" catchup-days="5",Flussonic
            http://stream.example/c/index.m3u8
            #EXTINF:-1,Plain
            http://stream.example/d
            """.trimIndent()
        )

        assertEquals(CatchupType.SHIFT, playlist.channels[0].catchupType)
        assertEquals(7, playlist.channels[0].catchupDays)
        assertEquals(CatchupType.APPEND, playlist.channels[1].catchupType)
        assertEquals(CatchupType.FLUSSONIC, playlist.channels[2].catchupType)
        assertEquals(CatchupType.NONE, playlist.channels[3].catchupType)
        assertEquals(0, playlist.channels[3].catchupDays)
    }

    @Test
    fun `skips a URL that has no EXTINF and keeps parsing`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            http://orphan.example/stream
            #EXTINF:-1,Good
            http://stream.example/good
            """.trimIndent()
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Good", playlist.channels.single().name)
    }

    @Test
    fun `tolerates unquoted attribute values and unknown directives`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id=abc.tv group-title=Kids,Cartoon Channel
            #KODIPROP:inputstream=inputstream.adaptive
            http://stream.example/7
            """.trimIndent()
        )
        val channel = playlist.channels.single()
        assertEquals("abc.tv", channel.tvgId)
        assertEquals("Kids", channel.group)
        assertNull(channel.logo)
    }

    @Test
    fun `handles a byte order mark on the first line`() {
        val playlist = M3uParser.parse("﻿#EXTM3U\n#EXTINF:-1,A\nhttp://a\n")
        assertTrue(playlist.channels.isNotEmpty())
    }
}
