package app.tinypod.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's preferences, kept in SharedPreferences and observable for the UI and the player. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  init {
    // The speed used to live in the player's own preferences.
    if (!prefs.contains(KEY_SPEED)) {
      val legacy = context.getSharedPreferences("player", Context.MODE_PRIVATE)
      if (legacy.contains(KEY_SPEED)) prefs.edit { putFloat(KEY_SPEED, legacy.getFloat(KEY_SPEED, 1f)) }
    }
  }

  private val _clearOlderOnFinish = MutableStateFlow(prefs.getBoolean(KEY_CLEAR_OLDER, true))

  /** Finishing (or marking played) an episode makes the podcast's older episodes no longer new. */
  val clearOlderOnFinish: StateFlow<Boolean> = _clearOlderOnFinish.asStateFlow()

  private val _continueWithNext = MutableStateFlow(prefs.getBoolean(KEY_CONTINUE, false))

  /** When an episode ends with nothing queued, play the podcast's next newer unplayed episode. */
  val continueWithNext: StateFlow<Boolean> = _continueWithNext.asStateFlow()

  private val _rewindOnResumeSec = MutableStateFlow(prefs.getInt(KEY_REWIND, 10))

  /** How far to go back when resuming after a pause of a minute or more; 0 for not at all. */
  val rewindOnResumeSec: StateFlow<Int> = _rewindOnResumeSec.asStateFlow()

  private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f))

  /** The playback speed, shared by every episode. */
  val speed: StateFlow<Float> = _speed.asStateFlow()

  fun setClearOlderOnFinish(value: Boolean) {
    prefs.edit { putBoolean(KEY_CLEAR_OLDER, value) }
    _clearOlderOnFinish.value = value
  }

  fun setContinueWithNext(value: Boolean) {
    prefs.edit { putBoolean(KEY_CONTINUE, value) }
    _continueWithNext.value = value
  }

  fun setRewindOnResumeSec(value: Int) {
    prefs.edit { putInt(KEY_REWIND, value) }
    _rewindOnResumeSec.value = value
  }

  fun setSpeed(value: Float) {
    prefs.edit { putFloat(KEY_SPEED, value) }
    _speed.value = value
  }

  companion object {
    /** The rewind amounts offered, in seconds. */
    val REWIND_CHOICES = listOf(0, 5, 10, 15, 30)

    private const val KEY_CLEAR_OLDER = "clearOlderOnFinish"
    private const val KEY_CONTINUE = "continueWithNext"
    private const val KEY_REWIND = "rewindOnResumeSec"
    private const val KEY_SPEED = "speed"
  }
}
