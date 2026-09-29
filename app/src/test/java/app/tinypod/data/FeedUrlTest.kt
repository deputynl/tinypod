package app.tinypod.data

import app.tinypod.data.PodcastRepository.Companion.looksLikeUrl
import app.tinypod.data.PodcastRepository.Companion.normalizeFeedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedUrlTest {
  @Test
  fun `normalizes pasted feed URLs`() {
    assertEquals("https://example.com/feed", normalizeFeedUrl("  https://example.com/feed "))
    assertEquals("http://example.com/feed", normalizeFeedUrl("http://example.com/feed"))
    assertEquals("https://example.com/feed", normalizeFeedUrl("feed://example.com/feed"))
    assertEquals("https://example.com/feed", normalizeFeedUrl("itpc://example.com/feed"))
    assertEquals("https://feeds.example.com/show", normalizeFeedUrl("feeds.example.com/show"))
  }

  @Test
  fun `tells URLs apart from search terms`() {
    assertTrue(looksLikeUrl("https://example.com/feed"))
    assertTrue(looksLikeUrl("feeds.megaphone.fm/abc123"))
    assertFalse(looksLikeUrl("hardcore history"))
    assertFalse(looksLikeUrl("radiolab"))
    assertFalse(looksLikeUrl("99% invisible"))
  }
}
