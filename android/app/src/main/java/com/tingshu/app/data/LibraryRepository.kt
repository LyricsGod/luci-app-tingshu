package com.tingshu.app.data

import java.text.Collator
import java.util.Locale

/** 把服务端原始书库 + meta + 合集拼成界面直接能用的数据。 */
class LibraryRepository(
    private val api: TingshuApi,
    private val covers: CoverStore,
) {

    suspend fun load(base: String): LibrarySnapshot {
        val raw = api.fetchLibrary(base)
        // meta / collections 拉不到不算致命：书还是能听
        val meta = runCatching { api.fetchMeta(base) }.getOrDefault(emptyMap())

        val collator = Collator.getInstance(Locale.CHINA)
        val books = raw.books
            .mapNotNull { toBook(base, it, meta) }
            .sortedWith(Comparator { a, b -> collator.compare(a.title, b.title) })

        val collections = runCatching { api.fetchCollections(base) }.getOrNull()
        return LibrarySnapshot(books, collections, raw.root, raw.error)
    }

    private fun toBook(base: String, raw: TingshuApi.RawBook, meta: Map<String, MetaRecord>): Book? {
        val id = "net:" + raw.id
        val hex = Hex.encode(id)
        val m = meta[hex]

        val tracks = raw.tracks
            .filter { it.path.isNotBlank() }
            .sortedWith(Comparator { a, b -> naturalCompare(a.path, b.path) })
            .map { Track(cleanTrackTitle(it.title), it.path, Urls.media(base, it.path)) }
        if (tracks.isEmpty()) return null

        val coverFile = m?.coverBase64?.let { covers.save(hex, it) }
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
}
