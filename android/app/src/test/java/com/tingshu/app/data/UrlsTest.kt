package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    @Test
    fun `补默认端口与 scheme`() {
        assertEquals("http://192.168.1.1:8686/", Urls.normalizeServer("192.168.1.1"))
        assertEquals("http://192.168.1.1:8686/", Urls.normalizeServer("192.168.1.1:8686"))
        assertEquals("http://192.168.1.1:8686/", Urls.normalizeServer("  http://192.168.1.1:8686/  "))
        assertEquals("https://demo.example.com:8686/", Urls.normalizeServer("https://demo.example.com"))
    }

    @Test
    fun `反向代理的子路径不会被端口插错`() {
        assertEquals("https://example.com:8686/tingshu/", Urls.normalizeServer("https://example.com/tingshu"))
        assertEquals("http://192.168.1.1:8686/tingshu/", Urls.normalizeServer("192.168.1.1:8686/tingshu/"))
    }

    @Test
    fun `非法地址返回 null`() {
        assertNull(Urls.normalizeServer("   "))
        assertNull(Urls.normalizeServer("http://"))
    }

    @Test
    fun `媒体地址按 encodeURIComponent 规则编码`() {
        assertEquals(
            "http://192.168.1.1:8686/media/%E4%B8%89%E4%BD%93/01%20%E5%9C%B0%E7%90%83%E5%BE%80%E4%BA%8B.mp3",
            Urls.media("http://192.168.1.1:8686/", "三体/01 地球往事.mp3"),
        )
        assertEquals("%2F", Urls.encodeSegment("/"))
        assertEquals("a-b_c.d~e!f*g'h(i)", Urls.encodeSegment("a-b_c.d~e!f*g'h(i)"))
        assertEquals("%25", Urls.encodeSegment("%"))
    }

    @Test
    fun `hostLabel 适合直接显示`() {
        assertEquals("192.168.1.1:8686", Urls.hostLabel("http://192.168.1.1:8686/"))
        assertEquals("example.com:8686/tingshu", Urls.hostLabel("https://example.com:8686/tingshu/"))
    }

    @Test
    fun `地址分栏编辑能拆出协议主机端口`() {
        assertEquals(
            Urls.ServerParts("http", "192.168.2.1", "8686"),
            Urls.splitServer("192.168.2.1:8686"),
        )
        assertEquals(
            Urls.ServerParts("https", "ting.example.com", "443"),
            Urls.splitServer("https://ting.example.com:443"),
        )
        assertEquals(
            Urls.ServerParts("https", "example.com", "", "/tingshu"),
            Urls.splitServer("https://example.com/tingshu/"),
        )
        assertEquals(
            Urls.ServerParts("http", "ting.example.com", ""),
            Urls.splitServer("ting.example.com"),
        )
    }

    @Test
    fun `分栏拼回的地址不带手打冒号且能被归一化`() {
        assertEquals("https://ting.example.com:443", Urls.joinServer("https", "ting.example.com", "443"))
        assertEquals("http://192.168.2.1", Urls.joinServer("http", "192.168.2.1", ""))
        assertEquals(
            "https://example.com/tingshu",
            Urls.joinServer("https", "example.com", "", "/tingshu"),
        )
        assertEquals(
            "https://ting.example.com:443/",
            Urls.normalizeServer(Urls.joinServer("https", "ting.example.com", "443")),
        )
        assertEquals(
            "http://192.168.2.1:8686/",
            Urls.normalizeServer(Urls.joinServer("http", "192.168.2.1", "8686")),
        )
    }
}
