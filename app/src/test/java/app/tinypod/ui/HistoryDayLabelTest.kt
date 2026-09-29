package app.tinypod.ui

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDayLabelTest {
  private val zone = ZoneId.of("Europe/Amsterdam")
  // Tuesday 29 September 2026, 00:30 local time: just after midnight, to catch day-boundary mistakes.
  private val now = at(2026, 9, 29, 0, 30)

  private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

  private fun label(playedAt: Long) = historyDayLabel(playedAt, now, zone, Locale.US)

  @Test fun `same day is Today`() = assertEquals("Today", label(at(2026, 9, 29, 0, 5)))

  @Test fun `just before midnight is Yesterday`() = assertEquals("Yesterday", label(at(2026, 9, 28, 23, 55)))

  @Test fun `within the past week is the weekday`() = assertEquals("Wednesday", label(at(2026, 9, 23)))

  @Test fun `a week or more ago is the date`() = assertEquals("Sep 22, 2026", label(at(2026, 9, 22)))

  @Test fun `a slightly future timestamp still counts as Today`() = assertEquals("Today", label(at(2026, 9, 29, 9, 0)))
}
