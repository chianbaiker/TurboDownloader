package com.turbo.downloader.core

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

object NetworkClient {

    val okhttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun probe(url: String, referer: String = ""): ProbeResult {
        val request = Request.Builder()
            .url(url)
            .method("HEAD", null)
            .header("User-Agent", USER_AGENT)
            .apply { if (referer.isNotEmpty()) header("Referer", referer) }
            .build()

        okhttp.newCall(request).execute().use { response ->
            val headers = response.headers
            val supportsRanges = headers["Accept-Ranges"]?.equals("bytes", ignoreCase = true) == true ||
                    headers["Content-Range"] != null
            val contentLength = headers["Content-Length"]?.toLongOrNull() ?: -1L
            val contentType = headers["Content-Type"] ?: ""
            val disposition = headers["Content-Disposition"] ?: ""

            if (!response.isSuccessful && response.code != 405) {
                val rangeRequest = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .apply { if (referer.isNotEmpty()) header("Referer", referer) }
                    .build()
                okhttp.newCall(rangeRequest).execute().use { r2 ->
                    val h2 = r2.headers
                    val len = h2["Content-Range"]?.substringAfterLast("/")?.toLongOrNull() ?: -1L
                    return ProbeResult(
                        supportsRanges = r2.code == 206,
                        contentLength = if (len > 0) len else (h2["Content-Length"]?.toLongOrNull() ?: -1L),
                        contentType = h2["Content-Type"] ?: "",
                        fileName = extractFileName(disposition, url),
                        finalUrl = r2.request.url.toString()
                    )
                }
            }

            return ProbeResult(
                supportsRanges = supportsRanges || response.code == 206,
                contentLength = contentLength,
                contentType = contentType,
                fileName = extractFileName(disposition, url),
                finalUrl = response.request.url.toString()
            )
        }
    }

    fun buildRequest(url: String, referer: String, range: String? = null): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Connection", "keep-alive")
            .apply { if (referer.isNotEmpty()) header("Referer", referer) }
            .apply { if (range != null) header("Range", range) }
            .get()
            .build()
    }

    private fun extractFileName(disposition: String, url: String): String {
        disposition.substringAfter("filename*=", "").substringAfter("UTF-8''").let {
            if (it.isNotEmpty() && it != disposition) return@extractFileName it.trim().trim('"')
        }
        disposition.substringAfter("filename=", "").let {
            if (it.isNotEmpty() && it != disposition) return@extractFileName it.trim().trim('"')
        }
        return runCatching { url.toHttpUrl().pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() } ?: "download.bin" }
            .getOrDefault("download.bin")
    }

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 TurboDownloader/1.0"

    data class ProbeResult(
        val supportsRanges: Boolean,
        val contentLength: Long,
        val contentType: String,
        val fileName: String,
        val finalUrl: String
    )
}
