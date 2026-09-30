package app.tinypod.ui

import app.tinypod.data.Episode
import app.tinypod.data.EpisodeWithPodcast
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeSearchTest {
  private fun row(id: Long, title: String, notes: String? = null) =
    EpisodeWithPodcast(Episode(id = id, podcastId = 1, guid = "g$id", title = title, audioUrl = "", publishedAt = 0, description = notes), "Show", null)

  private val search =
    EpisodeSearch(
      listOf(
        row(1, "Grass Apes", "<p>Primatologists on <a href=\"https://x.example\">bonobos</a> &amp; grass.</p>"),
        row(2, "The Sweetest Thing", "A story about sugar, with Jane Goodall."),
        row(3, "Café Society", null),
        row(4, "Apes Revisited", "More on grass."),
        row(5, "Mixtapes", "Grasshoppers' songs."),
      )
    )

  private fun ids(query: String) = search.search(query).map { it.episode.id }

  @Test fun `blank query returns everything in order`() = assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids("  "))

  @Test fun `matches the start of words, not their middle`() = assertEquals(listOf(1L, 4L), ids("apes"))

  @Test fun `a prefix finds longer words and punctuation is ignored`() = assertEquals(listOf(5L), ids("grasshopper's"))

  @Test fun `matches titles ignoring case`() = assertEquals(listOf(2L), ids("SWEETEST"))

  @Test fun `all words must match, in any order, across title and notes`() = assertEquals(listOf(1L, 4L), ids("apes grass"))

  @Test fun `title matches come before show-note matches`() = assertEquals(listOf(1L, 4L, 5L), ids("grass"))

  @Test fun `searches show notes`() = assertEquals(listOf(2L), ids("goodall"))

  @Test fun `ignores accents both ways`() {
    assertEquals(listOf(3L), ids("cafe"))
    assertEquals(listOf(3L), ids("CAFÉ"))
  }

  @Test fun `ignores HTML markup but decodes entities`() {
    assertEquals(emptyList<Long>(), ids("href"))
    assertEquals(listOf(1L), ids("bonobos & grass"))
  }

  @Test fun `strips tags and decodes numeric entities`() =
    assertEquals(" Tom &  Jerry’s ", EpisodeSearch.stripHtml("<b>Tom &amp; </b>Jerry&#8217;s<br/>"))
}
