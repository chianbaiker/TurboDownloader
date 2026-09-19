package com.turbo.downloader.sniffer

import com.turbo.downloader.core.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

data class MediaItem(
    val url: String,
    val type: String = TYPE_FILE, // video | audio | file | m3u8 | dash
    val name: String = "",
    val title: String? = null,
    val pageUrl: String? = null,
    val size: Long = -1L,
    val contentType: String = ""
) {
    companion object {
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
        const val TYPE_FILE = "file"
        const val TYPE_HLS = "m3u8"
        const val TYPE_DASH = "dash"
    }
}

/**
 * Webpage sniffer: 抓取页面并抽取可下载资源。
 * 检测：
 *  - <video>/<audio>/<source>
 *  - <a href> 指向媒体 / 文档 / 压缩包
 *  - m3u8 / mpd (HLS / DASH) manifest
 *  - OpenGraph / twitter video cards
 *  - manifest 内嵌分片 URL（深度解析，提升覆盖率）
 *
 * 新增（IDM 核心能力）：
 *  嗅探到 m3u8 / mpd 后由 [MediaDownloadCoordinator] 自动路由到
 *  [HlsDownloader] / [DashDownloader] 完成分片合并，产出单个 .mp4。
 */
object Sniffer {

    suspend fun sniff(pageUrl: String, referer: String = ""): List<MediaItem> = withContext(Dispatchers.IO) {
        val request = NetworkClient.buildRequest(pageUrl, referer)
        val response = NetworkClient.okhttp.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        val baseUri = response.request.url.toString()
        val doc: Document = Jsoup.parse(body, baseUri)

        val items = LinkedHashSet<MediaItem>()

        // 1. video / audio / source tags
        doc.select("video, audio, source").forEach { el ->
            val src = el.absUrl("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.absUrl("file") }
            if (src.isNotEmpty()) {
                val tag = el.tagName()
                items.add(mediaItem(src, if (tag.contains("audio")) TYPE_AUDIO else TYPE_VIDEO, pageUrl))
            }
        }

        // 2. meta og:video / twitter:player:stream
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter\\:player\\:stream]").forEach { el ->
            val content = el.attr("content")
            if (content.isNotEmpty()) items.add(mediaItem(resolve(baseUri, content), TYPE_VIDEO, pageUrl))
        }

        // 3. HLS / DASH manifests
        doc.select("source[src]").forEach { el ->
            val src = el.absUrl("src")
            when {
                src.endsWith(".m3u8") || src.contains("m3u8") -> items.add(mediaItem(src, TYPE_HLS, pageUrl))
                src.endsWith(".mpd") -> items.add(mediaItem(src, TYPE_DASH, pageUrl))
            }
        }

        // 4. all <a href> matching media extensions or containing video keywords
        doc.select("a[href]").forEach { el ->
            val href = el.absUrl("href")
            if (href.isNotEmpty() && isMedia(href)) {
                items.add(mediaItem(href, typeOf(href), pageUrl, label = el.text().ifBlank { null }))
            }
        }

        // 5. regex fallback for manifests embedded in JS
        Regex("""https?://[^\s"']+\.(?:m3u8|mpd)(?:\?[^\s"']*)?""").findAll(body).forEach {
            items.add(mediaItem(it.value, if (it.value.contains("mpd")) TYPE_DASH else TYPE_HLS, pageUrl))
        }

        // 6. 深度解析：若页面本身就是 m3u8/mpd，抽取内部分片 URL
        if (body.trimStart().startsWith("#EXTM3U") || body.contains("<MPD", ignoreCase = true)) {
            items.addAll(parseManifestInternal(body, baseUri, pageUrl))
        }

        // 7. 页面标题作为默认文件名
        val pageTitle = doc.title().ifBlank { null }
        items.toList().map { it.copy(title = it.title ?: pageTitle, pageUrl = it.pageUrl ?: pageUrl) }
    }

    /** 解析 manifest 内部指向的 media playlist / 分片，提升嗅探覆盖率。 */
    private fun parseManifestInternal(body: String, baseUri: String, pageUrl: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        if (body.trimStart().startsWith("#EXTM3U")) {
            body.lines().forEach { raw ->
                val trimmed = raw.trim()
                if (!trimmed.startsWith("#") && trimmed.isNotBlank()) {
                    val resolved = resolve(baseUri, trimmed)
                    out.add(
                        when {
                            resolved.endsWith(".ts", ignoreCase = true) || resolved.contains(".ts?", ignoreCase = true) ->
                                mediaItem(resolved, TYPE_VIDEO, pageUrl)
                            else -> mediaItem(resolved, TYPE_HLS, pageUrl)
                        }
                    )
                }
            }
        } else {
            // MPD：抽取 BaseURL / SegmentURL media
            Regex("""(BaseURL|media|initialization)="([^"]+)"""").findAll(body).forEach {
                val resolved = resolve(baseUri, it.groupValues[2])
                if (resolved.contains(".mp4", ignoreCase = true) ||
                    resolved.contains(".webm", ignoreCase = true) ||
                    resolved.contains("m4", ignoreCase = true)
                ) {
                    out.add(mediaItem(resolved, TYPE_VIDEO, pageUrl))
                }
            }
        }
        return out
    }

    private fun mediaItem(url: String, type: String, pageUrl: String? = null, label: String? = null): MediaItem =
        MediaItem(url = url, type = type, name = label ?: nameFrom(url), title = label ?: nameFrom(url), pageUrl = pageUrl)

    private fun isMedia(url: String): Boolean {
        return MEDIA_EXT.any { url.endsWith(it, ignoreCase = true) } ||
                url.contains("m3u8", ignoreCase = true) ||
                url.contains(".mpd", ignoreCase = true) ||
                (url.contains("video", ignoreCase = true) && url.contains("token", ignoreCase = true))
    }

    private fun typeOf(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> TYPE_HLS
        url.contains(".mpd", ignoreCase = true) -> TYPE_DASH
        else -> TYPE_FILE
    }

    private fun nameFrom(url: String): String =
        runCatching { URL(url).path.split("/").last().takeIf { it.isNotEmpty() } ?: "media" }.getOrDefault("media")

    private fun resolve(base: String, relative: String): String =
        runCatching { URL(URL(base), relative).toString() }.getOrDefault(relative)

    private val MEDIA_EXT = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".wmv", ".m4v",
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a",
        ".pdf", ".zip", ".rar", ".7z", ".tar.gz", ".apk", ".epub"
    )
}
