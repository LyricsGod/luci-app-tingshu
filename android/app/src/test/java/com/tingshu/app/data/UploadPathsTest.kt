package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPathsTest {

    @Test
    fun `单本书文件夹不剥外层`() {
        val names = listOf("三体/01 地球往事.mp3", "三体/02 红岸之谜.mp3")
        assertEquals(names, stripWrapperRoot(names))
    }

    @Test
    fun `装着多本书的大文件夹剥掉外层`() {
        val names = listOf(
            "我的听书库/三体/01.mp3",
            "我的听书库/明朝那些事儿/01.mp3",
        )
        assertEquals(
            listOf("三体/01.mp3", "明朝那些事儿/01.mp3"),
            stripWrapperRoot(names),
        )
    }

    @Test
    fun `CD 分盘目录不剥外层`() {
        val names = listOf("鬼吹灯/CD1/01.mp3", "鬼吹灯/CD2/02.mp3")
        assertEquals(names, stripWrapperRoot(names))
    }

    @Test
    fun `根目录直接有音频时整个文件夹就是一本书`() {
        val names = listOf("三体/01.mp3", "三体/续集/01.mp3")
        assertEquals(names, stripWrapperRoot(names))
    }

    @Test
    fun `路径规范化处理斜杠和上级目录`() {
        assertEquals("三体/01.mp3", normalizeEntryPath("/三体/01.mp3"))
        assertEquals("三体/01.mp3", normalizeEntryPath("三体\\01.mp3"))
        assertNull(normalizeEntryPath("../etc/passwd"))
        assertNull(normalizeEntryPath("   "))
    }

    @Test
    fun `上传白名单过滤`() {
        assertTrue(isUploadable("三体/01.mp3"))
        assertTrue(isUploadable("三体/cover.JPG"))
        assertTrue(!isUploadable("三体/01.exe"))
        assertTrue(!isUploadable("三体/"))
    }
}
