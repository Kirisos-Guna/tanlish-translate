package com.example.tanlishkeyboard.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The one GET that the free web translators need, plus the headers they expect.
 *
 * Google's translate endpoint and MyMemory both answer a plain GET with no key and no account, and
 * both would rather see a browser than a bare client, so the request carries the same mobile Chrome
 * user agent the typed path already sends to easytamiltyping.
 */
internal object KeylessHttp {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    /** Percent encodes a query parameter as UTF-8, which is what Tamil script needs. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun get(url: String): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json, */*")
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
