package com.turbo.downloader.sniffer

import org.junit.Assert.*
import org.junit.Test

class MediaSnifferTest {

    @Test
    fun typeDetection_correctForExtensions() {
        assertEquals(MediaItem.TYPE_HLS, typeOf("https://cdn.com/video/index.m3u8?token=abc"))
        assertEquals(MediaItem.TYPE_DASH, typeOf("https://cdn.com/rep1.mpd"))
        assertEquals(MediaItem.TYPE_VIDEO, typeOf("https://cdn.com/movie.mp4"))
        assertEquals(MediaItem.TYPE_AUDIO, typeOf("https://cdn.com/song.mp3"))
        assertEquals(MediaItem.TYPE_FILE, typeOf("https://cdn.com/book.pdf"))
    }

    @Test
    fun mediaItem_carriesTitleAndPageUrl() {
        val item = MediaItem("https://a.com/1.m3u8", MediaItem.TYPE_HLS, "stream", title = "My Stream", pageUrl = "https://a.com/watch")
        assertEquals("My Stream", item.title)
        assertEquals("https://a.com/watch", item.pageUrl)
        assertEquals(MediaItem.TYPE_HLS, item.type)
    }

    @Test
    fun nameFrom_extractsLastPathSegment() {
        assertEquals("video.mp4", nameFrom("https://cdn.com/path/to/video.mp4"))
        assertEquals("media", nameFrom("https://cdn.com/"))
    }

    @Test
    fun parseManifestInternal_extractsTsSegments() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            seg-0.ts
            #EXTINF:10.0,
            seg-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val items = parseManifest(m3u8, "http://s.com/playlist.m3u8", "http://s.com/page")
        assertEquals(2, items.size)
        assertTrue(items.all { it.type == MediaItem.TYPE_VIDEO })
        assertEquals("http://s.com/seg-1.ts", items[1].url)
    }

    @Test
    fun parseManifestInternal_extractsMpdSegments() {
        val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>
            <AdaptationSet contentType="video"><Representation id="v1">
                <BaseURL>videoinit.mp4</BaseURL>
                <SegmentList><SegmentURL media="seg1.m4s"/></SegmentList>
            </Representation></AdaptationSet></Period></MPD>"""
        val items = parseManifest(mpd, "http://s.com/manifest.mpd", "http://s.com/page")
        assertTrue(items.any { it.url.endsWith("videoinit.mp4") || it.url.endsWith("seg1.m4s") })
    }

    // 反射访问 Sniffer 私有方法（保持与 iOS 版静态自检一致的验证强度）
    private fun parseManifest(body: String, baseUri: String, pageUrl: String): List<MediaItem> {
        val m = Sniffer::class.java.getDeclaredMethod("parseManifestInternal", String::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, body, baseUri, pageUrl) as List<MediaItem>
    }

    private fun typeOf(url: String): String {
        val m = Sniffer::class.java.getDeclaredMethod("typeOf", String::class.java)
        m.isAccessible = true
        return m.invoke(null, url) as String
    }

    private fun nameFrom(url: String): String {
        val m = Sniffer::class.java.getDeclaredMethod("nameFrom", String::class.java)
        m.isAccessible = true
        return m.invoke(null, url) as String
    }
}
