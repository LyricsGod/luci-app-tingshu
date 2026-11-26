package com.tingshu.app.data

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Collator
import java.util.Locale

/** 把服务端原始书库 + meta + 合集拼成界面直接能用的数据。 */
class LibraryRepository(
    private val api: TingshuApi,
    private val covers: CoverStore,
    private val shelf: ShelfSnapshotStore,
    private val cache: CacheStore,
) {

    /**
     * 冷启动专用：直接读预构建快照（见 [ShelfSnapshotStore]）。
     *
     * 这里不解析原始 library.json、不排序章节、不解码封面 base64，
     * 反序列化完就是终态 Book 列表，界面可以立刻画书架。
     * 没有快照时返回 null，走 [loadCached] 兜底，再走 [load] 网络刷新。
     */
    suspend fun loadShelfCached(base: String): LibrarySnapshot? = coroutineScope {
        val snapshot = shelf.read(base) ?: return@coroutineScope null
        if (snapshot.books.isEmpty()) return@coroutineScope null
        Log.i(TAG, "快照: ${snapshot.books.size} 本 / ${snapshot.savedAt} / ${snapshot.libraryHash}")
        LibrarySnapshot(snapshot.books, snapshot.collections, snapshot.root, null)
    }

    /**
     * 三个接口并发拉取。以前是串行 await，弱网下 library(500KB) + meta(1MB) + 合集
     * 的耗时是相加的，连接时就要转好几秒；并发后总耗时取决于最慢的那个。
     *
     * meta / collections 拉不到不算致命（书还是能听），但必须留下日志——
     * 之前用 runCatching 静默吞掉，自定义封面消失时完全查不到原因。
     */
    suspend fun load(base: String, sync: TingshuApi.SyncPayload?): LibrarySnapshot = coroutineScope {
        // 三个数据源各自判断：服务端指纹 == 本地已存指纹 且 本地有缓存 → 只读盘，
        // 不再走网络。书库和 meta 加起来近 3MB，书没改动时这部分流量直接省掉。
        val libraryJob = async { libraryFrom(base, sync?.librarySha) }
        val metaJob = async { metaFrom(base, sync?.metaSha) }
        val collectionsJob = async { collectionsFrom(base, sync?.collectionsSha) }

        val text = libraryJob.await()
        val raw = api.parseLibrary(text)
        val snapshot = assemble(base, raw, metaJob.await(), collectionsJob.await(), "网络")

        // 快照写入只跟 library 指纹绑定：内容没变就不重写，避免每次启动都做一次大文件 IO。
        // meta 的变化由 metaSha 单独判断（书名/封面改了 library.json 不会变）。
        val hash = sync?.librarySha?.takeIf { it != "none" }
            ?: ShelfSnapshotStore.fingerprint(text)
        val metaHash = sync?.metaSha.orEmpty()
        val existing = shelf.read(base)
        if (existing?.libraryHash != hash || existing.metaHash != metaHash ||
            existing.books.size != snapshot.books.size
        ) {
            shelf.write(
                base,
                ShelfSnapshot(
                    books = snapshot.books,
                    collections = snapshot.collections,
                    root = snapshot.root,
                    libraryHash = hash,
                    metaHash = metaHash,
                    savedAt = System.currentTimeMillis(),
                ),
            )
        }
        snapshot
    }

    /** 老服务端没有 /cgi-ts/sync 时走这里：三个接口照旧全量拉。 */
    suspend fun load(base: String): LibrarySnapshot = load(base, null)

    private suspend fun libraryFrom(base: String, sha: String?): String {
        val key = CacheStore.libraryKey(base)
        val shaKey = CacheStore.libraryShaKey(base)
        if (sha != null && sha != "none" && cache.read(shaKey) == sha) {
            cache.read(key)?.let {
                Log.i(TAG, "library 指纹一致，跳过下载 (${it.length}B)")
                return it
            }
        }
        val text = api.fetchLibraryText(base)
        if (sha != null && sha != "none") cache.write(shaKey, sha)
        return text
    }

    private suspend fun metaFrom(base: String, sha: String?): Map<String, MetaRecord> {
        val key = CacheStore.metaKey(base)
        val shaKey = CacheStore.metaShaKey(base)
        if (sha != null && sha != "none" && cache.read(shaKey) == sha && cache.has(key)) {
            Log.i(TAG, "meta 指纹一致，跳过下载")
            return api.fetchMeta(base, cacheOnly = true)
        }
        val map = fetchMetaSafely(base)
        if (sha != null && sha != "none") cache.write(shaKey, sha)
        return map
    }

    private suspend fun collectionsFrom(base: String, sha: String?): List<Collection>? {
        val key = CacheStore.collectionsKey(base)
        val shaKey = CacheStore.collectionsShaKey(base)
        if (sha != null && sha != "none" && cache.read(shaKey) == sha && cache.has(key)) {
            Log.i(TAG, "collections 指纹一致，跳过下载")
            return api.fetchCollections(base, cacheOnly = true).takeIf { it.isNotEmpty() }
        }
        val list = fetchCollectionsSafely(base)
        if (sha != null && sha != "none") cache.write(shaKey, sha)
        return list
    }

    /**
     * 只用本地原始缓存拼一份快照，供没有预构建快照时兜底（首次安装 / 清过缓存）。
     */
    suspend fun loadCached(base: String): LibrarySnapshot? = coroutineScope {
        val raw = api.fetchLibraryCached(base) ?: return@coroutineScope null
        val meta = api.fetchMeta(base, cacheOnly = true)
        val collections = api.fetchCollections(base, cacheOnly = true).takeIf { it.isNotEmpty() }
        assemble(base, raw, meta, collections, "缓存")
    }

    /** 本地改过书名/封面后调用：快照失效，下次网络刷新重建。 */
    fun invalidateShelf(base: String) = shelf.invalidate(base)

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
