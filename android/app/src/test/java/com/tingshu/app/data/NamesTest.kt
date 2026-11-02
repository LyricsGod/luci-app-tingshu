package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {

    @Test
    fun `自然排序把第二章排在第十章前面`() {
        val names = listOf("10 第十章.mp3", "2 第二章.mp3", "1 第一章.mp3")
        val sorted = names.sortedWith(Comparator { a, b -> naturalCompare(a, b) })
        assertEquals(listOf("1 第一章.mp3", "2 第二章.mp3", "10 第十章.mp3"), sorted)
    }

    @Test
    fun `cleanTrackTitle 去掉章节序号`() {
        assertEquals("地球往事", cleanTrackTitle("01. 地球往事"))
        assertEquals("红岸之谜", cleanTrackTitle("003 - 红岸之谜"))
        assertEquals("死神永生", cleanTrackTitle("4）死神永生"))
        assertEquals("1984", cleanTrackTitle("1984"))
    }

    @Test
    fun `没有标题时回退到原文件名`() {
        val name = "01. mp3"
        assertTrue(cleanTrackTitle(name).isNotEmpty())
    }
}
