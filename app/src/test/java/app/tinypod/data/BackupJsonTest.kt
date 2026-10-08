package app.tinypod.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupJsonTest {
  private val snapshot =
    BackupSnapshot(
      exportedAt = 1_790_000_000_000,
      settings = BackupSettings(clearOlderOnFinish = false, continueWithNext = true, rewindOnResumeSec = 15, speed = 1.25f),
      folders = listOf(BackupFolder("News", 0), BackupFolder("Comedy", 1)),
      podcasts =
        listOf(
          BackupPodcast(
            feedUrl = "https://a.example/rss",
            title = "Show A",
            folder = "News",
            newSince = 3_001,
            episodes = listOf(BackupEpisode("a1", isPlayed = true, positionMs = 0, lastPlayedAt = 5_000), BackupEpisode("a2", isPlayed = false, positionMs = 60_000, lastPlayedAt = null)),
          ),
          BackupPodcast(feedUrl = "https://b.example/rss", title = "Show B", folder = null, newSince = 0, episodes = emptyList()),
        ),
      queue = listOf(QueueEntry("https://a.example/rss", "a2"), QueueEntry("https://b.example/rss", "b9")),
    )

  @Test
  fun `round trip`() {
    assertEquals(snapshot, Backup.parse(Backup.toJson(snapshot)))
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects an unknown version`() {
    Backup.parse(Backup.toJson(snapshot).replace("\"version\": 1", "\"version\": 2"))
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects other JSON`() {
    Backup.parse("""{"results": []}""")
  }

  @Test(expected = BackupFormatException::class)
  fun `rejects something that isn't JSON`() {
    Backup.parse("<opml/>")
  }
}
