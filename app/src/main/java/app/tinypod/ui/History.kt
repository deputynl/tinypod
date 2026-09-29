package app.tinypod.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tinypod.theme.TinypodTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HistoryScreen(vm: TabsViewModel) {
  val rows by vm.history.collectAsStateWithLifecycle()
  val now = System.currentTimeMillis()
  EpisodeList(
    rows,
    empty = "Nothing played yet.",
    inHistory = true,
    currentId = currentEpisodeId(),
    sectionOf = { historyDayLabel(it.episode.lastPlayedAt ?: 0, now) },
    onEvent = rememberEpisodeEventHandler(),
  )
}

/** "Today", "Yesterday", a weekday within the past week, else the date: the day an episode was last listened to. */
internal fun historyDayLabel(playedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
  val day = Instant.ofEpochMilli(playedAt).atZone(zone).toLocalDate()
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  return when {
    !day.isBefore(today) -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
  }
}

// Previews

@Preview(showBackground = true)
@Composable
private fun HistoryPreview() {
  val now = 1_790_000_000_000
  val rows = previewEpisodeRows.mapIndexed { i, row -> row.copy(episode = row.episode.copy(lastPlayedAt = now - i * 40_000_000L)) }
  TinypodTheme { EpisodeList(rows, empty = "", inHistory = true, sectionOf = { historyDayLabel(it.episode.lastPlayedAt!!, now) }) }
}
