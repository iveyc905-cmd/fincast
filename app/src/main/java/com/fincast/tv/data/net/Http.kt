package com.fincast.tv.data.net

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Many providers reject requests that do not look like a set-top box. */
const val DEFAULT_USER_AGENT = "Fincast/0.1 (Android; TV)"

object Http {

    @Volatile private var client: OkHttpClient? = null

    fun client(context: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // EPG downloads can be very long
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024))
            .build()
            .also { client = it }
    }

    /**
     * Streams a URL to [block]. The response is closed when [block] returns, so
     * the stream must not escape it.
     */
    fun <T> stream(
        context: Context,
        url: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
        block: (InputStream) -> T,
    ): T {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent ?: DEFAULT_USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        client(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Drop the query string: for Xtream it carries the password, and this
                // message ends up on screen.
                throw IOException("HTTP ${response.code} from ${url.substringBefore('?')}")
            }
            val body = response.body ?: throw IOException("Empty body for $url")
            return block(body.byteStream())
        }
    }

    fun text(
        context: Context,
        url: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = stream(context, url, userAgent, headers) { it.readBytes().toString(Charsets.UTF_8) }
}
