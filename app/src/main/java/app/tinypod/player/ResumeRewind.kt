package app.tinypod.player

/** Pauses shorter than this resume exactly where they stopped. */
const val REWIND_AFTER_PAUSE_MS = 60_000L

/**
 * Where to resume after a pause: [rewindMs] before [positionMs] (not before the start) if the pause,
 * from [pausedAt] until [now], lasted at least [REWIND_AFTER_PAUSE_MS]. Null to stay where it is.
 */
fun resumePosition(positionMs: Long, pausedAt: Long, now: Long, rewindMs: Long): Long? {
  if (rewindMs <= 0 || now - pausedAt < REWIND_AFTER_PAUSE_MS) return null
  return (positionMs - rewindMs).coerceAtLeast(0)
}
