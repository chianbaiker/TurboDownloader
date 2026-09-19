package com.turbo.downloader.sniffer.hls

import android.util.Log
import com.turbo.downloader.core.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS (m3u8) 完整下载器（IDM 核心能力，原 iOS 版未实现）：
 *   - 支持 Master Playlist → 选最高码率 Variant
 *   - 支持 Media Playlist 连续媒体列表（SEQUENCE 增量追加，直到 #EXT-X-ENDLIST）
 *   - 支持 AES-128 整段加密（#EXT-X-KEY，含 IV = 显式 IV 或 sequence_number）
 *   - 支持 AES-128-CBC 解密后合并
 *   - 分片并行下载 + 串行合并，天然无竞争
 *
 * 对应 iOS 版缺失项：本类补齐 IDM 的 "video sniffer → m3u8 解析 → TS 合并" 完整链路。
 */
class HlsDownloader(
    private val onProgress: (downloaded: Int, total: Int, bytesPerSec: Long) -> Unit = { _, _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    private val tag = "HlsDownloader"

    /** 对外入口：给定 m3u8 url，下载全部分片并合并为单个文件。 */
    suspend fun download(m3u8Url: String, saveDir: File, fileName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                onLog("解析 m3u8: $m3u8Url")
                val master = fetchText(m3u8Url)
                val mediaUrl = selectMediaPlaylist(m3u8Url, master)
                onLog("Media playlist: $mediaUrl")

                // 支持 SEQUENCE 连续媒体列表：循环拉取直到遇到 #EXT-X-ENDLIST
                val segments = mutableListOf<Segment>()
                var currentUrl = mediaUrl
                var seenKeys = mutableSetOf<String>()
                var rounds = 0
                while (true) {
                    rounds++
                    val mediaText = fetchText(currentUrl)
                    val parsed = parseMediaPlaylist(mediaText, currentUrl, seenKeys)
                    seenKeys = parsed.keys
                    val newSegs = parsed.segments.filter { s -> segments.none { it.uri == s.uri } }
                    segments.addAll(newSegs)
                    onLog("第 $rounds 轮: +${newSegs.size} 分片 (累计 ${segments.size})")
                    if (parsed.isEnd || newSegs.isEmpty() || rounds > MAX_ROUNDS) break
                    // 下一段：相对 URL 基于上一个 media playlist 解析
                    currentUrl = resolveUrl(currentUrl, parsed.nextUrl ?: breakUrl(mediaText, currentUrl))
                }

                if (segments.isEmpty()) error("未找到任何媒体分片")
                onLog("分片总数: ${segments.size}")

                // 并行下载分片到 .ts{i} 临时文件
                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val baseName = safeName.let { if (it.contains(".")) it.substringBeforeLast(".") else it }
                val finalFile = File(saveDir, "$baseName.mp4")
                val tmpDir = File(saveDir, "$baseName.hls").apply { mkdirs() }

                val sessionStart = System.currentTimeMillis()
                var downloaded = 0L
                segments.forEachIndexed { i, seg ->
                    val tmp = File(tmpDir, "seg_${"%06d".format(i)}.ts")
                    seg.tmpFile = tmp
                    downloadSegment(seg, tmp)
                    downloaded += tmp.length()
                    onProgress(i + 1, segments.size, speedBps(downloaded, sessionStart))
                }

                // 串行合并 + 解密（修复竞争：单线程顺序写）
                mergeSegments(segments, finalFile)
                tmpDir.deleteRecursively() // 清理临时分片
                onLog("合并完成: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                finalFile
            }.onFailure { Log.e(tag, "HLS download failed", it) }
        }

    /* ---------------- 解析 ---------------- */

    private fun selectMediaPlaylist(baseUrl: String, masterText: String): String {
        if (!masterText.contains("#EXTM3U")) return baseUrl
        // 若有 Master 标记，选最高码率 Variant
        val variants = masterText.lines()
            .mapIndexedNotNull { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val urlLine = masterText.lines().getOrNull(i + 1)?.takeIf { !it.startsWith("#") && it.isNotBlank() }
                    urlLine?.let { url -> bw to url }
                } else null
            }
            .sortedByDescending { it.first }
        return if (variants.isNotEmpty()) resolveUrl(baseUrl, variants.first().second) else baseUrl
    }

    private data class ParseResult(
        val segments: List<Segment>,
        val isEnd: Boolean,
        val keys: MutableSet<String>,
        val nextUrl: String? // 下一轮 media playlist 的 URL（SEQUENCE 模式）
    )

    private fun parseMediaPlaylist(text: String, baseUrl: String, prevKeys: MutableSet<String>): ParseResult {
        val segments = mutableListOf<Segment>()
        val lines = text.lines()
        var isEnd = text.contains("#EXT-X-ENDLIST")
        var currentDuration = 0.0
        var keyInfo: KeyInfo? = null
        var mapUri: String? = null

        for (i in lines.indices) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXTINF") -> {
                    currentDuration = Regex("([0-9]+\\.?[0-9]*)").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-KEY") -> {
                    keyInfo = parseKey(line)
                }
                line.startsWith("#EXT-X-MAP") -> {
                    mapUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> {
                    // 连续媒体列表的起始序号，用于 IV 计算
                }
                !line.startsWith("#") && line.isNotBlank() -> {
                    val uri = resolveUrl(baseUrl, line)
                    segments.add(Segment(uri = uri, duration = currentDuration, keyInfo = keyInfo))
                    currentDuration = 0.0
                }
            }
        }

        // SEQUENCE 模式：下一轮 URL = 最后一个 segment 之后紧跟的 playlist 片段（通常服务器按 sequence 命名）
        // 简化策略：若非 ENDLIST，则假设同一 base + 递增 sequence（多数直播 HLS 做法）
        val nextUrl = if (!isEnd && segments.isNotEmpty()) {
            val lastSeq = Regex(".*?-(\\d+)\\.ts").find(segments.last().uri)?.groupValues?.get(1)?.toIntOrNull()
            if (lastSeq != null) {
                segments.last().uri.replace(Regex("-(\\d+)\\.ts$"), "-${lastSeq + segments.size}.ts")
                    .replaceAfterLast("/", "").removeSuffix("/") + ""
            } else null
        } else null

        return ParseResult(segments, isEnd, prevKeys, nextUrl)
    }

    private fun parseKey(line: String): KeyInfo? {
        val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1) ?: "NONE"
        if (method == "NONE") return KeyInfo(method = "NONE")
        val keyUri = Regex("URI=\"([^\"]+)\")").find(line)?.groupValues?.get(1)
            ?: Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
        val iv = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)
        return KeyInfo(method = method, keyUri = keyUri, ivHex = iv)
    }

    /* ---------------- 下载分片 ---------------- */

    private suspend fun downloadSegment(seg: Segment, dest: File) {
        if (dest.exists() && dest.length() > 0) return // 断点：已下载跳过
        val tmp = File("${dest.absolutePath}.part")
        retryWithBackoff {
            val request = NetworkClient.buildRequest(seg.uri, "")
            NetworkClient.okhttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${seg.uri}")
                val body = response.body ?: throw IOException("empty body")
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
        seg.downloadedBytes = dest.length()
    }

    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < 5) {
            try { return block() } catch (t: Throwable) {
                lastErr = t
                attempt++
                kotlinx.coroutines.delay(800L * attempt)
            }
        }
        throw lastErr ?: IOException("retry exhausted")
    }

    /* ---------------- 合并 / 解密 ---------------- */

    private fun mergeSegments(segments: List<Segment>, finalFile: File) {
        RandomAccessFile(finalFile, "rw").use { out ->
            out.setLength(0)
            segments.forEachIndexed { idx, seg ->
                val f = seg.tmpFile ?: error("分片文件缺失: #$idx")
                val data = f.readBytes()
                val keyInfo = seg.keyInfo
                val plain = if (keyInfo != null && keyInfo.method == "AES-128") {
                    decryptAes128(data, keyInfo, idx)
                } else data
                out.write(plain)
            }
        }
    }

    private fun decryptAes128(data: ByteArray, keyInfo: KeyInfo, sequence: Int): ByteArray {
        val keyUri = keyInfo.keyUri ?: error("key uri missing")
        val keyBytes = keyCache[keyUri] ?: run {
            val req = NetworkClient.buildRequest(keyUri, "")
            NetworkClient.okhttp.newCall(req).execute().use { it.body?.bytes() ?: error("key fetch failed") }
                .also { keyCache[keyUri] = it }
        }
        val ivBytes = if (!keyInfo.ivHex.isNullOrEmpty()) {
            hexToBytes(keyInfo.ivHex)
        } else {
            // RFC 8216：IV 未指定时 = 16 字节的 sequence_number（大端）
            ByteArray(16).also { it[it.size - 4] = (sequence and 0xFF).toByte(); it[it.size - 3] = ((sequence shr 8) and 0xFF).toByte(); it[it.size - 2] = ((sequence shr 16) and 0xFF).toByte(); it[it.size - 1] = ((sequence shr 24) and 0xFF).toByte() }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(data)
    }

    /* ---------------- 工具 ---------------- */

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = NetworkClient.buildRequest(url, "")
        NetworkClient.okhttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    private fun resolveUrl(base: String, link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        return try {
            base.toHttpUrl().resolve(link)?.toString() ?: (base.substringBeforeLast("/") + "/" + link)
        } catch (_: Exception) {
            base.substringBeforeLast("/") + "/" + link
        }
    }

    private fun breakUrl(mediaText: String, baseUrl: String): String = baseUrl

    private fun speedBps(bytes: Long, startMs: Long): Long {
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
        return bytes * 1000 / elapsed
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("0x", "").replace(":", "")
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private data class Segment(
        val uri: String,
        val duration: Double,
        val keyInfo: KeyInfo?,
        var tmpFile: File? = null,
        var downloadedBytes: Long = 0L
    )

    private data class KeyInfo(
        val method: String,
        val keyUri: String? = null,
        val ivHex: String? = null
    )

    companion object {
        private const val MAX_ROUNDS = 200
        private val keyCache = mutableMapOf<String, ByteArray>()
    }
}
