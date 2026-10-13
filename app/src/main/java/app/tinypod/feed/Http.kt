package app.tinypod.feed

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** What a conditional GET found: the body is unchanged, or here it is with the validators for next time. */
sealed interface Conditional<out T> {
  data object NotModified : Conditional<Nothing>

  data class Fetched<T>(val value: T, val etag: String?, val lastModified: String?) : Conditional<T>
}

/** Tiny wrapper around HttpURLConnection; the app only ever needs plain GETs. */
object Http {
  const val USER_AGENT = "Tinypod/1.0 (Android podcast app)"
  /** Podcast audio URLs often chain several tracking redirects (5 is common), so allow plenty. */
  private const val MAX_REDIRECTS = 10

  /** GETs [url] and hands the body stream to [read]. Follows redirects, including http <-> https hops. */
  suspend fun <T> get(url: String, read: (InputStream) -> T): T = request(url) { conn, _ -> conn.inputStream.use(read) }

  /**
   * GETs [url] unless it's unchanged since the response that gave [etag] and [lastModified] (either
   * may be null, both for an unconditional GET). Servers that ignore the validators just send the body.
   */
  suspend fun <T> getIfChanged(url: String, etag: String?, lastModified: String?, read: (InputStream) -> T): Conditional<T> {
    val headers = buildMap {
      etag?.let { put("If-None-Match", it) }
      lastModified?.let { put("If-Modified-Since", it) }
    }
    return request(url, headers, onNotModified = { Conditional.NotModified }) { conn, _ ->
      Conditional.Fetched(conn.inputStream.use(read), conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
    }
  }

  /** Follows [url]'s redirects and returns the URL that finally answers, without reading its body. */
  suspend fun resolveRedirects(url: String): String = request(url) { _, finalUrl -> finalUrl }

  private suspend fun <T> request(
    url: String,
    headers: Map<String, String> = emptyMap(),
    onNotModified: (() -> T)? = null,
    onSuccess: (HttpURLConnection, String) -> T,
  ): T =
    withContext(Dispatchers.IO) {
      var current = url
      repeat(MAX_REDIRECTS + 1) {
        val conn = URL(current).openConnection() as HttpURLConnection
        try {
          conn.instanceFollowRedirects = false // it refuses cross-protocol redirects, so we do it ourselves
          conn.connectTimeout = 15_000
          conn.readTimeout = 30_000
          conn.setRequestProperty("User-Agent", USER_AGENT)
          headers.forEach(conn::setRequestProperty)
          when (val code = conn.responseCode) {
            in 200..299 -> return@withContext onSuccess(conn, current)
            HttpURLConnection.HTTP_NOT_MODIFIED -> return@withContext onNotModified?.invoke() ?: throw HttpException(code, current)
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
