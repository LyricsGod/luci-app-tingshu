package com.tingshu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioCacheStoreTest {

    private fun newStore(): Pair<AudioCacheStore, File> {
        val root = File(System.getProperty("java.io.tmpdir"), "audio-cache-test-${System.nanoTime()}")
        root.mkdirs()
        return AudioCacheStore(root) to root
    }

    @Test
    fun retainKeepsOnlyBookWindow() {
        val (store, root) = newStore()
        val base = "http://router:8686/"
        val paths = (100..115).map { "book/$it.mp3" }

        paths.forEachIndexed { index, path ->
            val file = File(root, store.fileOf(base, path).name)
            file.writeBytes(ByteArray(2048))
            store.mark(base, path, "book-1", index)
        }

        // 当前在第 111 章（下标 10），向前保留 5 集、向后缓存 5 集 => 保留下标 5..15。
        val keep = 5..15
        val protectedFiles = keep.filter { it in paths.indices }.map { store.fileOf(base, paths[it]) }
        store.retain("book-1", keep, protectedFiles)

        assertEquals(11, store.count())
        assertTrue(store.cachedFile(base, paths[5]) != null)
        assertTrue(store.cachedFile(base, paths[15]) != null)
        assertFalse(store.cachedFile(base, paths[4]) != null)
        root.deleteRecursively()
    }

    @Test
    fun retainDoesNotTouchOtherBooks() {
        val (store, root) = newStore()
        val base = "http://router:8686/"
        val mine = "book/108.mp3"
        val other = "other/108.mp3"

        listOf(mine, other).forEach { path ->
            File(root, store.fileOf(base, path).name).writeBytes(ByteArray(2048))
            store.mark(base, path, if (path == mine) "book-1" else "book-2", 8)
        }

        store.retain("book-1", 8..13, listOf(store.fileOf(base, mine)))

        assertFalse(store.cachedFile(base, mine) == null)
        assertTrue(store.cachedFile(base, other) != null)
        root.deleteRecursively()
    }
}
