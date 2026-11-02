package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexTest {

    @Test
    fun `book id 的十六进制和网页端一致`() {
        // 网页 app.js: hexOf('net:三体') = UTF-8 字节的小写十六进制
        assertEquals("6e65743ae4b889e4bd93", Hex.encode("net:三体"))
        assertEquals("net:三体", Hex.decode("6e65743ae4b889e4bd93"))
    }

    @Test
    fun `散装音频的书 id 也能还原`() {
        val id = "net:__root__"
        assertEquals(id, Hex.decode(Hex.encode(id)))
    }

    @Test
    fun `非法十六进制返回 null`() {
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
        assertNull(Hex.decode(""))
    }
}
