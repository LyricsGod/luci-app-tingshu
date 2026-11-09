package com.tingshu.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 本地磁盘缓存：把服务端返回的 JSON 原文按 key 落盘。
 *
 * 用途是「先给界面看、再悄悄更新」——启动或进页面时先用这里的内容渲染，
 * 下拉刷新再走网络。网络失败时也用它兜底，至少不会白屏。
 *
 * 存的是原始响应，所以解析逻辑和网络路径完全一致，不会出现两份字段定义。
 */
class CacheStore(context: Context) {

    private val root = File(context.filesDir, "cache")

    private fun fileOf(key: String) = File(root, key + ".json")

    fun read(key: String): String? = runCatching {
        val f = fileOf(key)
        if (f.isFile) f.readText() else null
    }.onFailure { Log.w(TAG, "读缓存失败 $key: ${it.message}") }.getOrNull()

    fun write(key: String, body: String) {
        runCatching {
            root.mkdirs()
            fileOf(key).writeText(body)
        }.onFailure { Log.w(TAG, "写缓存失败 $key: ${it.message}") }
    }

    fun has(key: String): Boolean =
        runCatching { fileOf(key).let { it.isFile && it.length() > 0 } }.getOrDefault(false)

    /** 缓存占用的字节数，设置页显示用。 */
    fun sizeBytes(): Long = runCatching {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** 清空缓存，返回清掉的字节数。 */
    fun clear(): Long {
        val before = sizeBytes()
        runCatching {
            root.deleteRecursively()
            root.mkdirs()
        }.onFailure { Log.w(TAG, "清缓存失败: ${it.message}") }
        return before
    }

    companion object {
        private const val TAG = "TingshuCache"

        /** 同一台手机可能连过不同服务器，缓存按地址分开，避免串味。 */
        private fun tag(base: String) = Integer.toHexString(base.hashCode())

        fun libraryKey(base: String) = "library-${tag(base)}"
        fun metaKey(base: String) = "meta-${tag(base)}"
        fun collectionsKey(base: String) = "collections-${tag(base)}"
        fun durationsKey(base: String, bookHex: String) = "durations-${tag(base)}-$bookHex"
    }
}
