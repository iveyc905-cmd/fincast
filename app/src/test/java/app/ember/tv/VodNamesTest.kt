package app.ember.tv

import app.ember.tv.data.parse.VodNames
import app.ember.tv.data.parse.VodNames.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNamesTest {

    @Test
    fun `url path decides the kind first`() {
        assertEquals(Kind.MOVIE, VodNames.classify("http://h/movie/u/p/123.mkv", "Anything"))
        assertEquals(Kind.EPISODE, VodNames.classify("http://h/series/u/p/456.mp4", "Anything"))
        assertEquals(Kind.LIVE, VodNames.classify("http://h/live/u/p/789.ts", "News HD"))
        assertEquals(Kind.LIVE, VodNames.classify("http://h/u/p/789", "News HD"))
    }

    @Test
    fun `video file extension falls back to the title`() {
        assertEquals(Kind.MOVIE, VodNames.classify("http://h/files/film.mp4?token=x", "Heat (1995)"))
        assertEquals(Kind.EPISODE, VodNames.classify("http://h/files/ep.mkv", "Dark S02E05"))
    }

    @Test
    fun `episode markers parse in common spellings`() {
        assertEquals(VodNames.EpisodeRef("Dark", 2, 5), VodNames.parseEpisode("Dark S02E05"))
        assertEquals(VodNames.EpisodeRef("The Office", 3, 12), VodNames.parseEpisode("The Office - S03 E12 - Safety Training"))
        assertEquals(VodNames.EpisodeRef("Show", 1, 1), VodNames.parseEpisode("Show.s01e01.720p"))
        assertNull(VodNames.parseEpisode("A film with no marker"))
    }

    @Test
    fun `years come out of titles`() {
        assertEquals(1995, VodNames.yearIn("Heat (1995)"))
        assertEquals(2019, VodNames.yearIn("Parasite - 2019"))
        assertNull(VodNames.yearIn("Blade Runner 2049"))
        assertEquals("Heat", VodNames.cleanTitle("Heat (1995)"))
    }

    @Test
    fun `show keys ignore case and punctuation`() {
        assertEquals(VodNames.showKey("The Office"), VodNames.showKey("the office."))
    }
}
