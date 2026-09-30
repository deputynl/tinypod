package app.tinypod.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Makes "next" and "previous" skip within the episode (forward 30 s, back 10 s), as podcast apps do.
 * The player holds one episode at a time, so it would otherwise report no next/previous at all: the
 * car hides those buttons and steering-wheel, Bluetooth and headset next/previous do nothing.
 */
class SkipButtonsPlayer(player: Player) : ForwardingPlayer(player) {
  private val skipCommands =
    intArrayOf(
      Player.COMMAND_SEEK_TO_NEXT,
      Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_PREVIOUS,
      Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

  /** Next/previous work whenever skipping within the episode does. */
  private val canSkip
    get() = super.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)

  override fun getAvailableCommands(): Player.Commands {
    val commands = super.getAvailableCommands()
    return if (canSkip) commands.buildUpon().addAll(*skipCommands).build() else commands
  }

  override fun isCommandAvailable(command: Int) = (canSkip && command in skipCommands) || super.isCommandAvailable(command)

  override fun hasNextMediaItem() = canSkip || super.hasNextMediaItem()

  override fun hasPreviousMediaItem() = canSkip || super.hasPreviousMediaItem()

  override fun seekToNext() = seekForward()

  override fun seekToNextMediaItem() = seekForward()

  override fun seekToPrevious() = seekBack()

  override fun seekToPreviousMediaItem() = seekBack()
}
