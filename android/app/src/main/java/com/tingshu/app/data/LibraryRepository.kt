package com.tingshu.app.data

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.text.Collator
import java.util.Locale

/** 把服务端原始书库 + meta + 合集拼成界面直接能用的数据。 */
class LibraryRepository(
    private val api: TingshuApi,
    private val covers: CoverStore,
) {

    /**
     * 三个接口并发拉取。以前是串行 await，弱网下 library(500KB) + meta(1MB) + 合集
     * 的耗时是相加的，连接时就要转好几秒；并发后总耗时取决于最慢的那个。
     *
     * meta / collections 拉不到不算致命（书还是能听），但必须留下日志——
     * 之前用 runCatching 静默吞掉，自定义封面消失时完全查不到原因。
     */
    suspend fun load(base: String): LibrarySnapshot = coroutineScope {
        val libraryJob = async { api.fetchLibrary(base) }
        val metaJob = async { fetchMetaSafely(base) }
        val collectionsJob = async { fetchCollectionsSafely(base) }

        val raw = libraryJob.await() ?: throw IOException("书库为空")
        assemble(base, raw, metaJob.await(), collectionsJob.await(), "网络")
    }

    /**
     * 只用本地缓存拼一份快照，供启动/进页面时先把书架画出来，随后再走网络刷新。
     * 没有缓存返回 null（这时界面照常等网络）。
     */
    suspend fun loadCached(base: String): LibrarySnapshot? = coroutineScope {
        val raw = api.fetchLibrary(base, cacheOnly = true) ?: return@coroutineScope null
        val meta = api.fetchMeta(base, cacheOnly = true)
        val collections = api.fetchCollections(base, cacheOnly = true).takeIf { it.isNotEmpty() }
        assemble(base, raw, meta, collections, "缓存")
    }

    private fun assemble(
        base: String,
        raw: TingshuApi.RawLibrary,
        meta: Map<String, MetaRecord>,
        collections: List<Collection>?,
        from: String,
    ): LibrarySnapshot {
        val collator = Collator.getInstance(Locale.CHINA)
        val books = raw.books
            .mapNotNull { toBook(base, it, meta) }
            .sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) })

        val withCover = books.count { it.coverFile != null || it.coverUrl != null }
        Log.i(TAG, "$from: ${books.size} 本 / meta ${meta.size} 条 / 有封面 $withCover 本")

        return LibrarySnapshot(books, collections, raw.root, raw.error)
    }

    private suspend fun fetchMetaSafely(base: String): Map<String, MetaRecord> =
        runCatching { api.fetchMeta(base) }
            .onFailure { Log.w(TAG, "meta 拉取失败: ${it.javaClass.simpleName} ${it.message}") }
            .getOrDefault(emptyMap())

    private suspend fun fetchCollectionsSafely(base: String): List<Collection>? =
        runCatching { api.fetchCollections(base) }
            .onFailure { Log.w(TAG, "collections 拉取失败: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()

    private fun toBook(base: String, raw: TingshuApi.RawBook, meta: Map<String, MetaRecord>): Book? {
        val id = "net:" + raw.id
        val hex = Hex.encode(id)
        val m = meta[hex]

        val tracks = raw.tracks
            .filter { it.path.isNotBlank() }
            .sortedWith(Comparator { a, b -> naturalCompare(a.path, b.path) })
            .map { Track(cleanTrackTitle(it.title), it.path, Urls.media(base, it.path)) }
        if (tracks.isEmpty()) return null

        val coverFile = m?.coverBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            covers.save(hex, b64).also {
                if (it == null) {
                    Log.w(TAG, "自定义封面解码/落盘失败: ${raw.title} (${b64.length} 字符)")
                }
            }
        }
        val folderCover = raw.cover?.takeIf { it.isNotBlank() }?.let { Urls.media(base, it) }

        return Book(
            id = id,
            folderId = raw.id,
            folderTitle = raw.title.ifBlank { raw.id },
            title = m?.title?.takeIf { it.isNotBlank() } ?: raw.title.ifBlank { raw.id },
            description = m?.description.orEmpty(),
            palette = m?.palette,
            coverFile = coverFile,
            coverUrl = folderCover,
            tracks = tracks,
        )
    }

    private companion object {
        const val TAG = "TingshuLib"
    }
}
