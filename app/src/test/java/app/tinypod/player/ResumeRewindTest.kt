package app.tinypod.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeRewindTest {
  @Test
  fun `short pauses resume where they stopped`() {
    assertNull(resumePosition(positionMs = 100_000, pausedAt = 0, now = 59_999, rewindMs = 10_000))
  }

  @Test
  fun `a pause of a minute or more goes back`() {
    assertEquals(90_000L, resumePosition(positionMs = 100_000, pausedAt = 0, now = 60_000, rewindMs = 10_000))
    assertEquals(70_000L, resumePosition(positionMs = 100_000, pausedAt = 0, now = 86_400_000, rewindMs = 30_000))
  }

  @Test
  fun `never goes back before the start`() {
    assertEquals(0L, resumePosition(positionMs = 4_000, pausedAt = 0, now = 120_000, rewindMs = 10_000))
  }

  @Test
  fun `zero means off`() {
    assertNull(resumePosition(positionMs = 100_000, pausedAt = 0, now = 120_000, rewindMs = 0))
  }
}
