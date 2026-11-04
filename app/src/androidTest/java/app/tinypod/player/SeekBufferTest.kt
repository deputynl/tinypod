package app.tinypod.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Skipping around what's already loaded shouldn't go back to the network; a local server counts the requests. */
@RunWith(AndroidJUnit4::class)
class SeekBufferTest {
  @Test
  fun skippingBackAndForwardStaysInTheBuffer() = assertEquals(0, requestsAfterSkipping(PlaybackService.bufferControl(), aheadMs = 200_000, forwardMs = 180_000, backMs = 20_000))

  /** Shows the test can fail: with the player's defaults, going back has to fetch the audio again. */
  @Test
  fun theDefaultBufferFetchesAgain() {
    assertTrue(requestsAfterSkipping(DefaultLoadControl(), aheadMs = 45_000, forwardMs = 40_000, backMs = 20_000) > 0)
  }

  private fun requestsAfterSkipping(control: LoadControl, aheadMs: Long, forwardMs: Long, backMs: Long): Int {
    val mp3 = silentMp3(minutes = 20)
    val requests = AtomicInteger()
    val server = ServerSocket(0)
    thread(isDaemon = true) { runCatching { while (true) { val socket = server.accept(); thread(isDaemon = true) { serve(socket, mp3, requests) } } } }
    val player = onMain {
      ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).setLoadControl(control).build().apply {
        volume = 0f
        setMediaItem(MediaItem.fromUri("http://127.0.0.1:${server.localPort}/a.mp3"))
        prepare()
      }
    }
    try {
      waitFor { onMain { player.playbackState == Player.STATE_READY && player.bufferedPosition >= aheadMs } }
      onMain { player.seekTo(forwardMs) }
      waitFor { onMain { player.playbackState == Player.STATE_READY } }
      val before = requests.get()
      onMain { player.seekTo(backMs) }
      waitFor { onMain { player.playbackState == Player.STATE_READY } }
      Thread.sleep(300) // a fetch would have started by now
      return requests.get() - before
    } finally {
      onMain { player.release() }
      server.close()
    }
  }

  /** Answers GETs, with Range support so the player can seek. */
  private fun serve(socket: java.net.Socket, body: ByteArray, requests: AtomicInteger) = runCatching {
    socket.use {
      val head = StringBuilder()
      val input = it.getInputStream()
      while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
      requests.incrementAndGet()
      val from = Regex("bytes=(\\d+)-").find(head)?.groupValues?.get(1)?.toInt() ?: 0
      val headers = "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/mpeg\r\nAccept-Ranges: bytes\r\nContent-Length: ${body.size - from}\r\n" +
        "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\nConnection: close\r\n\r\n"
      it.getOutputStream().apply { write(headers.toByteArray()); write(body, from, body.size - from); flush() }
    }
  }

  /** Constant-bitrate frames of silence: MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, so 417 bytes and 26 ms each. */
  private fun silentMp3(minutes: Int): ByteArray {
    val frame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0xC0.toByte() }
    return ByteArrayOutputStream().apply { repeat(minutes * 60 * 1000 / 26) { write(frame) } }.toByteArray()
  }

  private fun waitFor(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 20_000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "Timed out" }
      Thread.sleep(50)
    }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }
}
