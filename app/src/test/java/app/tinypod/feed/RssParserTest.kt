package app.tinypod.feed

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class RssParserTest {
  private val parser = RssParser { KXmlParser() }

  private fun sample() = parser.parse(javaClass.classLoader!!.getResourceAsStream("sample-feed.xml"))

  private fun millis(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

  @Test
  fun `parses channel metadata`() {
    val feed = sample()
    assertEquals("Sample & Show", feed.title)
    assertEquals("Sample Network", feed.author)
    assertEquals("A <b>sample</b> podcast.", feed.description)
    assertEquals("https://example.com/art.jpg", feed.artworkUrl)
  }

  @Test
  fun `parses items with enclosures and skips the rest`() {
    val episodes = sample().episodes
    assertEquals(listOf("ep-2", "https://cdn.example.com/ep1.mp3"), episodes.map { it.guid })

    val ep2 = episodes[0]
    assertEquals("Episode 2: Namespaced things", ep2.title)
    assertEquals("https://cdn.example.com/ep2.mp3", ep2.audioUrl)
    assertEquals(millis("2026-09-22T06:00:00Z"), ep2.publishedAt)
    assertEquals(3_723_000L, ep2.durationMs)
    assertEquals("<p>Rich show notes</p>", ep2.description)

    val ep1 = episodes[1]
    assertEquals("https://cdn.example.com/ep1.mp3", ep1.audioUrl)
    assertEquals(millis("2026-09-07T09:30:00-05:00"), ep1.publishedAt)
    assertEquals(2_710_000L, ep1.durationMs)
    assertEquals("Summary only", ep1.description)
  }

  @Test(expected = FeedParseException::class)
  fun `rejects non-RSS documents`() {
    parser.parse("<html><body>Not a feed</body></html>".byteInputStream())
  }

  @Test
  fun `parses common date variants`() {
    val expected = millis("2026-09-07T09:30:00Z")
    assertEquals(expected, RssParser.parseRssDate("Mon, 07 Sep 2026 09:30:00 GMT"))
    assertEquals(expected, RssParser.parseRssDate("Mon, 7 Sep 2026 09:30:00 +0000"))
    assertEquals(expected, RssParser.parseRssDate("7 Sep 2026 09:30:00 +0000"))
    assertEquals(expected, RssParser.parseRssDate("Mon,  7 Sep 2026 09:30 GMT"))
    assertEquals(expected, RssParser.parseRssDate("2026-09-07T09:30:00Z"))
    assertEquals(millis("2026-09-07T09:30:00-07:00"), RssParser.parseRssDate("Mon, 07 Sep 2026 09:30:00 PDT"))
    assertNull(RssParser.parseRssDate("sometime last week"))
  }

  @Test
  fun `parses durations`() {
    assertEquals(3_723_000L, RssParser.parseDuration("1:02:03"))
    assertEquals(3_723_000L, RssParser.parseDuration("62:03"))
    assertEquals(3_723_000L, RssParser.parseDuration("3723"))
    assertEquals(90_500L, RssParser.parseDuration("90.5"))
    assertNull(RssParser.parseDuration(""))
    assertNull(RssParser.parseDuration("1::2"))
    assertNull(RssParser.parseDuration("about an hour"))
  }
}
