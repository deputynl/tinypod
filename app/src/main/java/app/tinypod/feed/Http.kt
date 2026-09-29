package app.tinypod.feed

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Tiny wrapper around HttpURLConnection; the app only ever needs plain GETs. */
object Http {
  private const val USER_AGENT = "Tinypod/1.0 (Android podcast app)"
  private const val MAX_REDIRECTS = 5

  /** GETs [url] and hands the body stream to [read]. Follows redirects, including http <-> https hops. */
  suspend fun <T> get(url: String, read: (InputStream) -> T): T =
    withContext(Dispatchers.IO) {
      var current = url
      repeat(MAX_REDIRECTS + 1) {
        val conn = URL(current).openConnection() as HttpURLConnection
        try {
          conn.instanceFollowRedirects = false // it refuses cross-protocol redirects, so we do it ourselves
          conn.connectTimeout = 15_000
          conn.readTimeout = 30_000
          conn.setRequestProperty("User-Agent", USER_AGENT)
          when (val code = conn.responseCode) {
            in 200..299 -> return@withContext conn.inputStream.use(read)
            in 300..399 -> {
              val location = conn.getHeaderField("Location") ?: throw HttpException(code, current)
              current = URI(current).resolve(location).toString()
            }
            else -> throw HttpException(code, current)
          }
        } finally {
          conn.disconnect()
        }
      }
      throw IOException("Too many redirects for $url")
    }
}
