package app.tinypod.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer

class OpmlTest {
  private val opml = Opml({ KXmlParser() }, { KXmlSerializer() })

  private val snapshot =
    BackupSnapshot(
      exportedAt = 1_790_000_000_000,
      settings = BackupSettings(clearOlderOnFinish = false, continueWithNext = true, rewindOnResumeSec = 15, speed = 1.25f, hidePlayed = true),
      folders = listOf(BackupFolder("News", 0), BackupFolder("Empty", 1)),
      podcasts =
        listOf(
          BackupPodcast(
            feedUrl = "https://a.example/rss?x=1&y=2",
            title = "Show A & Friends",
            folder = "News",
            newSince = 3_001,
            episodes = listOf(BackupEpisode("a1", isPlayed = true, positionMs = 0, lastPlayedAt = 5_000), BackupEpisode("a2", isPlayed = false, positionMs = 60_000, lastPlayedAt = null)),
          ),
          BackupPodcast(feedUrl = "https://b.example/rss", title = "Show B", folder = null, newSince = 0),
        ),
      queue = listOf(QueueEntry("https://a.example/rss?x=1&y=2", "a2"), QueueEntry("https://b.example/rss", "b9")),
    )

  private fun write(s: BackupSnapshot) = ByteArrayOutputStream().also { opml.write(s, it) }.toString(Charsets.UTF_8)

  private fun read(text: String) = opml.read(text.byteInputStream())

  @Test
  fun `round trip`() {
    assertEquals(snapshot, read(write(snapshot)))
  }

  @Test
  fun `the export is plain OPML to other apps`() {
    val text = write(snapshot)
    assertTrue(text, text.contains("""<outline type="rss" text="Show A &amp; Friends" title="Show A &amp; Friends" xmlUrl="https://a.example/rss?x=1&amp;y=2""""))
    assertTrue(text, text.contains("""<outline type="rss" text="Show B" title="Show B" xmlUrl="https://b.example/rss""""))
  }

  @Test
  fun `reads another app's OPML`() {
    val other =
      """<?xml version="1.0" encoding="utf-8"?>
        <opml version="1.0">
          <head><title>Pocket Casts Feeds</title></head>
          <body>
            <outline text="feeds">
              <outline type="rss" text="Show A" xmlUrl="https://a.example/rss" />
              <outline text="Comedy">
                <outline type="rss" title="Show B" xmlUrl="feed://b.example/rss" />
              </outline>
              <outline type="rss" xmlUrl="https://c.example/rss" />
              <outline type="rss" text="Show A again" xmlUrl="https://a.example/rss" />
            </outline>
          </body>
        </opml>"""
    assertEquals(
      BackupSnapshot(
        exportedAt = null,
        settings = null,
        folders = listOf(BackupFolder("feeds", 0), BackupFolder("Comedy", 1)),
        podcasts =
          listOf(
            BackupPodcast("https://a.example/rss", "Show A", folder = "feeds", newSince = null),
            BackupPodcast("https://b.example/rss", "Show B", folder = "Comedy", newSince = null),
            BackupPodcast("https://c.example/rss", "https://c.example/rss", folder = "feeds", newSince = null),
          ),
        queue = emptyList(),
      ),
      read(other),
    )
  }

  @Test
  fun `podcasts outside any group stay unfiled`() {
    val other = """<opml version="2.0"><body><outline type="rss" text="A" xmlUrl="https://a.example/rss"/></body></opml>"""
    assertEquals(listOf(BackupPodcast("https://a.example/rss", "A", folder = null, newSince = null)), read(other).podcasts)
    assertEquals(emptyList<BackupFolder>(), read(other).folders)
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects an unknown version`() {
    read(write(snapshot).replace("""version="1"""", """version="2""""))
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects other XML`() {
    read("""<rss version="2.0"><channel/></rss>""")
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects the old JSON backups`() {
    read("""{"format": "tinypod-backup", "version": 1}""")
  }
}
