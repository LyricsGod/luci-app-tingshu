package com.tingshu.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavStackTest {

    @Test
    fun `合集到书籍到播放器后能连续返回到书架`() {
        val stack = NavStack()
        stack.go("collection")
        stack.go("book")
        stack.go("player")

        assertEquals("player", stack.current)
        stack.back()
        assertEquals("book", stack.current)
        stack.back()
        assertEquals("collection", stack.current)
        stack.back()
        assertEquals("library", stack.current)
    }

    @Test
    fun `回到书架后再返回不会改变当前页`() {
        val stack = NavStack(listOf("library", "book"))
        stack.back()
        stack.back()
        stack.back()
        assertEquals("library", stack.current)
        assertEquals(listOf("library"), stack.snapshot())
    }

    @Test
    fun `重复进入同一页面不会重复入栈`() {
        val stack = NavStack()
        stack.go("player")
        stack.go("player")
        stack.go("player")
        assertEquals(listOf("library", "player"), stack.snapshot())
        stack.back()
        assertEquals("library", stack.current)
    }

    @Test
    fun `从保存的状态恢复后返回路径依然完整`() {
        val saved = NavStack().apply {
            go("collection")
            go("book")
            go("player")
        }.snapshot()

        val restored = NavStack(saved)
        restored.back()
        assertEquals("book", restored.current)
        restored.back()
        assertEquals("collection", restored.current)
    }

    @Test
    fun `空状态或空白路由会回落到书架`() {
        assertEquals("library", NavStack(emptyList()).current)
        assertEquals(listOf("library"), NavStack(listOf("", "  ")).snapshot())
        val stack = NavStack()
        stack.go("")
        stack.go("  ")
        assertEquals(listOf("library"), stack.snapshot())
    }

    @Test
    fun `重置会清空整条历史`() {
        val stack = NavStack(listOf("library", "collection", "book"))
        stack.reset()
        assertEquals(listOf("library"), stack.snapshot())
    }
}
