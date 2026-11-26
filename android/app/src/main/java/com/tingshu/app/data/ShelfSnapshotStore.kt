package com.tingshu.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 预构建的书架快照。
 *
 * 旧启动链路：读 library.json 原文 → org.json 解析全部章节 → 每本书按自然序重排
 * 几千章 → 再从 meta 的 base64 里解码封面写盘 → 才能把 Book 列表交给界面。
 * 书一多（9000+ 章）这条路要走一两秒，用户看到的就是「打开要等一会儿」。
 *
 * 这里把「已经组装好的 Book 列表」直接落盘：章节顺序、书名、封面路径全都是终态。
 * 冷启动只读这一个文件，反序列化完就能画书架，不再做任何排序/解码/网络等待。
 * 网络刷新成功后在后台重写快照，下次启动就是最新的。
 *
 * 只存书架结构，不存进度 / 暂停记录——那两份数据实时性要求高，
 * 由 ProgressStore / PauseMarksStore 独立持久化。
 */
data class ShelfSnapshot(
    val books: List<Book>,
    val collections: List<Collection>?,
    val root: String,
    /** 生成这份快照时 library.json 的指纹，用于日志和后续增量刷新。 */
    val libraryHash: String,
    /** 生成这份快照时 meta（书名/简介/封面）的指纹。 */
    val metaHash: String = "",
    val savedAt: Long,
)

class ShelfSnapshotStore(context: Context) {

    private val dir = File(context.filesDir, "shelf")

    private fun fileOf(base: String): File =
        File(dir, "s-${Integer.toHexString(base.hashCode())}.json")

    fun read(base: String): ShelfSnapshot? {
        val f = fileOf(base)
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { parse(base, text) }
            .onFailure { Log.w(TAG, "书架快照损坏，忽略: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun write(base: String, snapshot: ShelfSnapshot) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val text = serialize(snapshot)
            val target = fileOf(base)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "书架快照写入失败: ${it.message}") }
    }

    /** 元数据被本地编辑后，快照里的书名/封面就过期了，删掉让下次刷新重建。 */
    fun invalidate(base: String) {
        runCatching { fileOf(base).delete() }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /* ---------------- 序列化 ---------------- */

    private fun serialize(s: ShelfSnapshot): String {
        val root = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_SAVED, s.savedAt)
            .put(KEY_ROOT, s.root)
            .put(KEY_HASH, s.libraryHash)
            .put(KEY_META_HASH, s.metaHash)

        val arr = JSONArray()
        for (b in s.books) {
            val o = JSONObject()
                .put(B_ID, b.id)
                .put(B_FOLDER, b.folderId)
                .put(B_FOLDER_TITLE, b.folderTitle)
                .put(B_TITLE, b.title)
                .put(B_DESC, b.description)
                .put(B_COVER, b.coverFile?.absolutePath.orEmpty())
                .put(B_COVER_URL, b.coverUrl.orEmpty())
            if (b.palette != null) o.put(B_PALETTE, b.palette)

            val tracks = JSONArray()
            for (t in b.tracks) {
                // 只存 title + path：url 用当前 base 现拼，快照更短，也不会存过期地址
                tracks.put(JSONArray().put(t.title).put(t.path))
            }
            o.put(B_TRACKS, tracks)
            arr.put(o)
        }
        root.put(KEY_BOOKS, arr)

        if (s.collections != null) {
            val cols = JSONArray()
            for (c in s.collections) {
                cols.put(
                    JSONObject()
                        .put(C_ID, c.id)
                        .put(C_NAME, c.name)
                        .put(C_BOOKS, JSONArray(c.bookIds))
                        .put(C_CREATED, c.createdAt)
                        .put(C_UPDATED, c.updatedAt)
                )
            }
            root.put(KEY_COLLECTIONS, cols)
        }
        return root.toString()
    }

    private fun parse(base: String, text: String): ShelfSnapshot {
        val json = JSONObject(text)
        require(json.optInt(KEY_VERSION) == VERSION) { "版本不匹配" }

        val books = ArrayList<Book>()
        val arr = json.optJSONArray(KEY_BOOKS) ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString(B_ID)
            if (id.isBlank()) continue

            val trackArr = o.optJSONArray(B_TRACKS) ?: JSONArray()
            val tracks = ArrayList<Track>(trackArr.length())
            for (k in 0 until trackArr.length()) {
                val pair = trackArr.optJSONArray(k) ?: continue
                val title = pair.optString(0)
                val path = pair.optString(1)
                if (path.isBlank()) continue
                tracks.add(Track(title, path, Urls.media(base, path)))
            }
            if (tracks.isEmpty()) continue

            val coverPath = o.optString(B_COVER)
            val coverFile = if (coverPath.isBlank()) null else File(coverPath).takeIf { it.isFile }

            books.add(
                Book(
                    id = id,
                    folderId = o.optString(B_FOLDER),
                    folderTitle = o.optString(B_FOLDER_TITLE),
                    title = o.optString(B_TITLE),
                    description = o.optString(B_DESC),
                    palette = if (o.has(B_PALETTE) && !o.isNull(B_PALETTE)) o.optInt(B_PALETTE) else null,
                    coverFile = coverFile,
                    coverUrl = o.optString(B_COVER_URL).ifBlank { null },
                    tracks = tracks,
                )
            )
        }

        val collections = json.optJSONArray(KEY_COLLECTIONS)?.let { cols ->
            ArrayList<Collection>(cols.length()).apply {
                for (i in 0 until cols.length()) {
                    val o = cols.optJSONObject(i) ?: continue
                    val id = o.optString(C_ID)
                    if (id.isBlank()) continue
                    val ids = ArrayList<String>()
                    o.optJSONArray(C_BOOKS)?.let { bag ->
                        for (k in 0 until bag.length()) {
                            val v = bag.optString(k)
                            if (v.isNotBlank()) ids.add(v)
                        }
                    }
                    add(
                        Collection(
                            id = id,
                            name = o.optString(C_NAME),
                            bookIds = ids,
                            createdAt = o.optLong(C_CREATED),
                            updatedAt = o.optLong(C_UPDATED),
                        )
                    )
                }
            }
        }

        return ShelfSnapshot(
            books = books,
            collections = collections,
            root = json.optString(KEY_ROOT),
            libraryHash = json.optString(KEY_HASH),
            metaHash = json.optString(KEY_META_HASH),
            savedAt = json.optLong(KEY_SAVED),
        )
    }

    companion object {
        private const val TAG = "TingshuShelf"
        private const val VERSION = 1

        private const val KEY_VERSION = "v"
        private const val KEY_SAVED = "at"
        private const val KEY_ROOT = "root"
        private const val KEY_HASH = "hash"
        private const val KEY_META_HASH = "mhash"
        private const val KEY_BOOKS = "books"
        private const val KEY_COLLECTIONS = "cols"

        private const val B_ID = "i"
        private const val B_FOLDER = "f"
        private const val B_FOLDER_TITLE = "ft"
        private const val B_TITLE = "t"
        private const val B_DESC = "d"
        private const val B_PALETTE = "p"
        private const val B_COVER = "c"
        private const val B_COVER_URL = "u"
        private const val B_TRACKS = "k"

        private const val C_ID = "i"
        private const val C_NAME = "n"
        private const val C_BOOKS = "b"
        private const val C_CREATED = "ct"
        private const val C_UPDATED = "ut"

        /** library.json 指纹：内容没变就不必重写快照。 */
        fun fingerprint(text: String): String = Integer.toHexString(text.hashCode())
    }
}
