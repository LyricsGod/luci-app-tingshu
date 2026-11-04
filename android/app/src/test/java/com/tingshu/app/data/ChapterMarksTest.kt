package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterMarksTest {

    @Test
    fun `全本已读会补全所有章节`() {
        assertEquals(listOf(0, 1, 2), ChapterMarks.apply(emptyList(), listOf(0, 1, 2), true, 3))
    }

    @Test
    fun `部分已读只加选中的章节`() {
        assertEquals(listOf(0, 2), ChapterMarks.apply(listOf(0), listOf(2), true, 3))
    }

    @Test
    fun `标未读只去掉选中的章节`() {
        assertEquals(listOf(0, 2), ChapterMarks.apply(listOf(0, 1, 2), listOf(1), false, 3))
    }

    @Test
    fun `全本未读清空并丢掉越界记录`() {
        assertEquals(emptyList<Int>(), ChapterMarks.apply(listOf(0, 1, 9), listOf(0, 1), false, 2))
    }

    @Test
    fun `越界下标被忽略`() {
        assertEquals(listOf(0), ChapterMarks.apply(emptyList(), listOf(-1, 0, 5), true, 3))
    }

    @Test
    fun `重复标记是幂等的`() {
        assertEquals(listOf(0, 1), ChapterMarks.apply(listOf(0, 1), listOf(1), true, 3))
    }
}
