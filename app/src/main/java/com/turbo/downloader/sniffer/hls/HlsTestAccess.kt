package com.turbo.downloader.sniffer.hls

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 仅供 `src/test` 使用的访问器（避免单元测试对私有类过度反射）。 */
internal object HlsTestAccess {

    fun parseMediaPlaylist(downloader: HlsDownloader, text: String, baseUrl: String): ParseResult {
        val m = HlsDownloader::class.java.getDeclaredMethod(
            "parseMediaPlaylist", String::class.java, String::class.java, MutableSet::class.java)
        m.isAccessible = true
        val raw = m.invoke(downloader, text, baseUrl, mutableSetOf<String>())
            ?: error("parseMediaPlaylist returned null")
        return ParseResult(raw)
    }

    fun selectMediaPlaylist(downloader: HlsDownloader, masterText: String, baseUrl: String): String {
        val m = HlsDownloader::class.java.getDeclaredMethod(
            "selectMediaPlaylist", String::class.java, String::class.java)
        m.isAccessible = true
        return (m.invoke(downloader, baseUrl, masterText) as? String)
            ?: error("selectMediaPlaylist returned null")
    }

    fun parseKey(downloader: HlsDownloader, line: String): KeyInfo {
        val m = HlsDownloader::class.java.getDeclaredMethod("parseKey", String::class.java)
        m.isAccessible = true
        val raw = m.invoke(downloader, line) ?: error("parseKey returned null")
        return KeyInfo(raw)
    }

    fun decryptAes128(downloader: HlsDownloader, data: ByteArray, keyInfo: KeyInfo, sequence: Int): ByteArray {
        val keyInfoClass = HlsDownloader::class.java.declaredClasses.first { it.simpleName == "KeyInfo" }
        val rawKeyInfo = keyInfoClass.getConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(keyInfo.method, keyInfo.keyUri, keyInfo.ivHex)
        val m = HlsDownloader::class.java.getDeclaredMethod(
            "decryptAes128", ByteArray::class.java, keyInfoClass, Int::class.javaPrimitiveType)
        m.isAccessible = true
        return (m.invoke(downloader, data, rawKeyInfo, sequence) as? ByteArray)
            ?: error("decryptAes128 returned null")
    }

    /** 把私有 ParseResult 包装为可断言的类型。 */
    class ParseResult(private val raw: Any) {
        val isEnd: Boolean get() = raw.javaClass.getField("isEnd").get(raw) as? Boolean ?: false
        val segments: List<Segment> = (raw.javaClass.getField("segments").get(raw) as? List<*>)
            ?.map { Segment(it ?: error("segment is null")) }
            ?: emptyList()
    }

    class Segment(private val raw: Any) {
        val uri: String get() = (raw.javaClass.getField("uri").get(raw) as? String).orEmpty()
        val duration: Double get() = (raw.javaClass.getField("duration").get(raw) as? Double) ?: 0.0
    }

    /** 与 HlsDownloader.KeyInfo 字段一一对应的可构造数据类。 */
    data class KeyInfo(
        val method: String,
        val keyUri: String? = null,
        val ivHex: String? = null
    ) {
        constructor(raw: Any) : this(
            method = (raw.javaClass.getField("method").get(raw) as? String).orEmpty(),
            keyUri = raw.javaClass.getField("keyUri").get(raw) as? String,
            ivHex = raw.javaClass.getField("ivHex").get(raw) as? String
        )
    }
}
