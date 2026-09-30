package app.tinypod.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowNotesTest {
  private fun positions(text: String, durationMs: Long? = null) = ShowNotes.timestamps(text, durationMs).map { text.substring(it.range) to it.positionMs }

  @Test fun `finds minute and hour timestamps`() =
    assertEquals(listOf("0:00" to 0L, "12:34" to 754_000L, "1:02:03" to 3_723_000L), positions("0:00 Intro\n12:34 The experiment (1:02:03) Outro"))

  @Test fun `ignores dates, versions and longer numbers`() =
    assertEquals(emptyList<Pair<String, Long>>(), positions("On 2026-09-30 at 10.30, v1.2:3, ratio 16:9:4, id 123:45:67:89"))

  @Test fun `skips times past the end of the episode`() =
    assertEquals(listOf("5:10" to 310_000L), positions("Chapter at 5:10, recorded at 18:45", durationMs = 600_000))

  @Test fun `rejects impossible hour timestamps`() = assertEquals(emptyList<Pair<String, Long>>(), positions("at 1:75:00"))

  @Test fun `plain text keeps its line breaks and is escaped`() =
    assertEquals("First line<br>Tom &lt;3 Jerry &amp; co", ShowNotes.toHtml("First line\r\nTom <3 Jerry & co"))

  @Test fun `html is kept as it is`() = assertEquals("<p>Rich <b>notes</b></p>", ShowNotes.toHtml("  <p>Rich <b>notes</b></p>\n"))
}
