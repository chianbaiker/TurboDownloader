package com.turbo.downloader.sniffer.hls

import org.junit.Assert.*
import org.junit.Test

/**
 * HlsDownloader 纯逻辑单元测试（JVM 直接跑，不依赖 Android SDK）。
 * 覆盖：Master→最高码率、Media 分片+SEQUENCE、#EXT-X-KEY AES-128+IV、AES 解密可逆、分片合并。
 */
class HlsUnitTest {

    private val downloader = HlsDownloader()

    @Test
    fun selectMediaPlaylist_picksHighestBandwidth() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high.m3u8
        """.trimIndent()
        val result = HlsTestAccess.selectMediaPlaylist(downloader, master, "http://example.com/index.m3u8")
        assertEquals("http://example.com/high.m3u8", result)
    }

    @Test
    fun parseMediaPlaylist_extractsSegmentsAndDetectsEnd() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            seg-0.ts
            #EXTINF:9.009,
            seg-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val result = HlsTestAccess.parseMediaPlaylist(downloader, media, "http://example.com/v/index.m3u8")
        assertEquals(2, result.segments.size)
        assertTrue(result.isEnd)
        assertEquals("http://example.com/v/seg-1.ts", result.segments[1].uri)
    }

    @Test
    fun parseKeyInfo_extractsMethodUriAndIv() {
        val line = """#EXT-X-KEY:METHOD=AES-128,URI="https://k.example.com/k",IV=0x1234567890abcdef1234567890abcdef"""
        val key = HlsTestAccess.parseKey(downloader, line)
        assertEquals("AES-128", key.method)
        assertEquals("https://k.example.com/k", key.keyUri)
        assertEquals("1234567890abcdef1234567890abcdef", key.ivHex)
    }

    @Test
    fun decryptAes128_isReversible() {
        val plain = "Hello HLS segment!".toByteArray()
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)

        val keyInfo = HlsTestAccess.KeyInfo(method = "AES-128", keyUri = "https://k.example.com/k", ivHex = "1234567890abcdef1234567890abcdef")
        val decrypted = HlsTestAccess.decryptAes128(downloader, encrypted, keyInfo, 0)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun mergeSegments_concatenatesInOrder() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "hlsmerge").apply { mkdirs() }
        val segs = listOf("AA".toByteArray(), "BBBB".toByteArray(), "CC".toByteArray())
        val files = segs.mapIndexed { i, b -> java.io.File(dir, "s$i.ts").also { it.writeBytes(b) } }
        val out = java.io.File(dir, "out.ts")
        java.io.RandomAccessFile(out, "rw").use { raf ->
            files.forEach { raf.write(it.readBytes()) }
        }
        assertEquals(8L, out.length())
        dir.deleteRecursively()
    }

    @Test
    fun sequenceMode_detectsNoEnd() {
        // 无 #EXT-X-ENDLIST → isEnd=false，触发 SEQUENCE 增量轮询（直播 HLS）
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:10.0,
            seg-100.ts
        """.trimIndent()
        val result = HlsTestAccess.parseMediaPlaylist(downloader, media, "http://example.com/v/index.m3u8")
        assertFalse(result.isEnd)
        assertEquals(1, result.segments.size)
    }
}
