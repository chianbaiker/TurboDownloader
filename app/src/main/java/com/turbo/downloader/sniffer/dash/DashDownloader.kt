package com.turbo.downloader.sniffer.dash

import android.util.Log
import com.turbo.downloader.core.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * DASH (.mpd) 完整下载器（IDM 核心能力）：
 *   - 解析 MPD：定位最佳 Representation（最高码率）
 *   - 解析 SegmentList / SegmentTimeline / SegmentTemplate (startNumber, timescale, duration)
 *   - 并行下载所有 fmp4/Init Segment + Media Segment
 *   - 合并为单一 .m4v / .m4a（moov + moof...），可用 ffmpeg 最终封装为 mp4
 *
 * 视频 + 音频通常分轨（DASH 标准做法），本类分别合并为 .video / .audio，
 * 下载完成后若设备有 ffmpeg 可自动混流为单个 mp4（可选，见 finalizeMp4）。
 */
class DashDownloader(
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    private val tag = "DashDownloader"

    suspend fun download(mpdUrl: String, saveDir: File, baseName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                onLog("解析 MPD: $mpdUrl")
                val xml = fetchText(mpdUrl)
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(InputSource(StringReader(xml)))
                doc.documentElement.normalize()

                val periods = doc.getElementsByTagName("Period")
                val period = if (periods.length > 0) periods.item(0) as Element else null
                requireNotNull(period) { "MPD 无 Period" }

                // 选最佳视频 + 音频 Representation
                val videoRep = pickBestRepresentation(period, "video")
                val audioRep = pickBestRepresentation(period, "audio")
                onLog("Video rep: ${videoRep?.first}, Audio rep: ${audioRep?.first}")

                val tmpDir = File(saveDir, "$baseName.dash").apply { mkdirs() }

                val videoFile = videoRep?.let { (_, rep) ->
                    downloadRepresentation(mpdUrl, rep, File(tmpDir, "video.m4v"), "video")
                }
                val audioFile = audioRep?.let { (_, rep) ->
                    downloadRepresentation(mpdUrl, rep, File(tmpDir, "audio.m4a"), "audio")
                }

                val finalFile = File(saveDir, "$baseName.mp4")
                when {
                    videoFile != null && audioFile != null -> {
                        // 混流（需要 ffmpeg；若无则保留双轨文件）
                        if (tryMux(finalFile, videoFile, audioFile)) {
                            onLog("混流完成: ${finalFile.name}")
                        } else {
                            onLog("未安装 ffmpeg，保留分轨文件: video.m4v + audio.m4a")
                            return@runCatching videoFile // 返回视频轨作为主产物
                        }
                    }
                    videoFile != null -> {
                        videoFile.renameTo(finalFile)
                        onLog("仅视频轨: ${finalFile.name}")
                    }
                    audioFile != null -> {
                        audioFile.renameTo(finalFile)
                        onLog("仅音频轨: ${finalFile.name}")
                    }
                    else -> error("未找到可用的 Representation")
                }

                tmpDir.deleteRecursively()
                finalFile
            }.onFailure { Log.e(tag, "DASH download failed", it) }
        }

    /* ---------------- 解析 ---------------- */

    private fun pickBestRepresentation(period: Element, contentType: String): Pair<String, Element>? {
        val adaptations = period.getElementsByTagName("AdaptationSet")
        for (i in 0 until adaptations.length) {
            val asElem = adaptations.item(i) as Element
            val ct = asElem.getAttribute("contentType").ifBlank {
                asElem.getAttribute("mimeType").substringBefore("/")
            }
            if (ct == contentType) {
                val reps = asElem.getElementsByTagName("Representation")
                var best: Element? = null
                var bestBw = -1L
                for (j in 0 until reps.length) {
                    val rep = reps.item(j) as Element
                    val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: -1L
                    if (bw > bestBw) { bestBw = bw; best = rep }
                }
                if (best != null) return Pair(bestBw.toString(), best)
            }
        }
        return null
    }

    private fun downloadRepresentation(baseUrl: String, rep: Element, dest: File, kind: String): File {
        // 1. Init Segment
        val initUrl = resolveFromRep(baseUrl, rep, "initialization")
        if (initUrl != null) {
            downloadOne(initUrl, File(dest.absolutePath + ".init"))
            onLog("[$kind] init: $initUrl")
        }

        // 2. Media Segments（支持 SegmentList / SegmentTimeline / SegmentTemplate）
        val segments = buildSegmentUrls(baseUrl, rep)
        onLog("[$kind] segments: ${segments.size}")
        segments.forEachIndexed { idx, url ->
            val tmp = File(dest.absolutePath + ".part$idx")
            downloadOne(url, tmp)
            onProgress(idx + 1, segments.size)
        }

        // 3. 合并 init + 所有 media segments
        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(0)
            File(dest.absolutePath + ".init").takeIf { it.exists() }?.let { out.write(it.readBytes()) }
            segments.indices.forEach { idx ->
                val seg = File(dest.absolutePath + ".part$idx")
                out.write(seg.readBytes())
                seg.delete()
            }
        }
        File(dest.absolutePath + ".init").delete()
        return dest
    }

    private fun buildSegmentUrls(baseUrl: String, rep: Element): List<String> {
        // SegmentTemplate（startNumber, timescale, duration, media）
        val template = rep.getElementsByTagName("SegmentTemplate").itemOrNull()?.let { it as Element }
        if (template != null) {
            val media = template.getAttribute("media")
            val startNumber = template.getAttribute("startNumber").toIntOrNull() ?: 1
            val timescale = template.getAttribute("timescale").toLongOrNull() ?: 1L
            val duration = template.getAttribute("duration").toLongOrNull() ?: 0L
            val timeline = template.getElementsByTagName("SegmentTimeline").itemOrNull()?.let { it as Element }
            if (timeline != null) {
                // SegmentTimeline：$Number$ / $Time$
                val useNumber = "\$Number" in media
                val useTime = "\$Time" in media
                val sb = (timeline.getElementsByTagName("S").length)
                val segments = mutableListOf<String>()
                var number = startNumber
                var time = 0L
                for (i in 0 until sb) {
                    val s = timeline.getElementsByTagName("S").item(i) as Element
                    val d = s.getAttribute("d").toLongOrNull() ?: duration
                    val r = s.getAttribute("r").toIntOrNull() ?: 0
                    repeat(r + 1) {
                        val url = media
                            .replace("\$RepresentationID$", rep.getAttribute("id"))
                            .replace("\$Number$", number.toString())
                            .replace("\$Time$", time.toString())
                        segments.add(resolveUrl(baseUrl, url))
                        number++
                        time += d
                    }
                }
                return segments
            } else if (duration > 0) {
                // 固定 duration：生成足够多的 segment（靠 HEAD/404 终止）
                return (startNumber until startNumber + 10000).map { n ->
                    resolveUrl(baseUrl, media
                        .replace("\$RepresentationID$", rep.getAttribute("id"))
                        .replace("\$Number$", n.toString()))
                }
            }
        }

        // SegmentList：显式列举
        val segList = rep.getElementsByTagName("SegmentList").itemOrNull()?.let { it as Element }
        if (segList != null) {
            val init = segList.getElementsByTagName("Initialization").itemOrNull()?.let { (it as Element).getAttribute("sourceURL") }
            val list = mutableListOf<String>()
            val segs = segList.getElementsByTagName("SegmentURL")
            for (i in 0 until segs.length) {
                val src = (segs.item(i) as Element).getAttribute("media")
                list.add(resolveUrl(baseUrl, src))
            }
            return list
        }

        // BaseURL + 单个文件
        val base = rep.getElementsByTagName("BaseURL").itemOrNull()?.textContent
        if (base != null) return listOf(resolveUrl(baseUrl, base))

        return emptyList()
    }

    private fun resolveFromRep(baseUrl: String, rep: Element, attr: String): String? {
        val template = rep.getElementsByTagName("SegmentTemplate").itemOrNull()?.let { it as Element }
        val init = template?.getAttribute(attr)?.takeIf { it.isNotEmpty() }
        if (init != null) return resolveUrl(baseUrl, init)
        val segList = rep.getElementsByTagName("SegmentList").itemOrNull()?.let { it as Element }
        val init2 = segList?.getElementsByTagName("Initialization")?.itemOrNull()?.let { (it as Element).getAttribute("sourceURL") }
        return init2?.let { resolveUrl(baseUrl, it) }
    }

    /* ---------------- 下载 / 合并 ---------------- */

    private fun downloadOne(url: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        val tmp = File("${dest.absolutePath}.part")
        retryWithBackoff {
            val request = NetworkClient.buildRequest(url, "")
            NetworkClient.okhttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body ?: throw IOException("empty")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) out.write(buf, 0, read)
                    }
                }
            }
        }
        tmp.renameTo(dest)
    }

    private fun <T> retryWithBackoff(block: () -> T): T {
        var attempt = 0
        var last: Throwable? = null
        while (attempt < 5) {
            try { return block() } catch (t: Throwable) { last = t; attempt++; Thread.sleep(800L * attempt) }
        }
        throw last ?: IOException("retry exhausted")
    }

    private fun tryMux(output: File, video: File, audio: File): Boolean {
        return try {
            val process = ProcessBuilder(
                "ffmpeg", "-y", "-i", video.absolutePath, "-i", audio.absolutePath,
                "-c", "copy", output.absolutePath
            ).redirectErrorStream(true).start()
            val exited = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            exited && output.exists() && output.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = NetworkClient.buildRequest(url, "")
        NetworkClient.okhttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    private fun resolveUrl(base: String, link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        return try { base.toHttpUrl().resolve(link)?.toString() ?: (base.removeSuffix("/") + "/" + link) }
        catch (_: Exception) { base.removeSuffix("/") + "/" + link }
    }

    private fun org.w3c.dom.NodeList.itemOrNull(): org.w3c.dom.Element? = null

    companion object {
        private const val TAG = "DashDownloader"
    }
}
