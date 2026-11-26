package com.tingshu.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 音频媒体缓存：按「当前章节 ± N 集」维护一个滑动窗口。
 *
 * 每个缓存文件都在 index.json 里记下所属书籍和章节下标，所以回收时只处理同一本书
 * 窗口外的章节，其它书的缓存不会误删。下载中的文件用 .part 命名，完整落盘后才改名。
 */
class AudioCacheStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "media-cache"))

    data class Entry(
        val bookId: String,
        val chapterIndex: Int,
        val savedAt: Long,
    )

    /** 缓存文件名：服务器地址 + 章节原始路径，换服务器后不会串味。 */
    fun fileOf(base: String, trackPath: String): File {
        val ext = extensionOf(trackPath)
        return File(root, "${tag(base)}-${hex(trackPath)}.$ext")
    }

    /** 只有完整文件才算命中；.part 是下载中的临时文件。 */
    fun cachedFile(base: String, trackPath: String): File? =
        fileOf(base, trackPath).takeIf { it.isFile && it.length() > 0L }

    fun has(base: String, trackPath: String): Boolean = cachedFile(base, trackPath) != null

    /** 把一个已存在的缓存文件登记到当前书和章节上，兼容旧版本留下的无名缓存。 */
    fun mark(base: String, trackPath: String, bookId: String, chapterIndex: Int) {
        val file = fileOf(base, trackPath)
        if (!file.isFile || file.length() <= 0L) return
        val index = loadIndex()
        index[file.name] = Entry(bookId, chapterIndex, System.currentTimeMillis())
        saveIndex(index)
    }

    fun sizeBytes(): Long = runCatching {
        audioFiles().sumOf { it.length() }
    }.getOrDefault(0L)

    fun count(): Int = runCatching { audioFiles().size }.getOrDefault(0)

    fun clear(): Long {
        val before = sizeBytes()
        runCatching {
            root.deleteRecursively()
            root.mkdirs()
        }.onFailure { Log.w(TAG, "清媒体缓存失败: ${it.message}") }
        return before
    }

    /**
     * 智能回收：同一本书只保留 [keep] 范围内的章节缓存。
     *
     * 先丢弃没有归属记录的旧文件（升级前下载的），再删掉本书窗口外的章节，
     * 最后如果总量超过上限，按最旧优先淘汰其它书的文件。
     * [protectedFiles] 是窗口内正在用/马上要用的文件，任何一步都不会删它们。
     */
    fun retain(
        bookId: String,
        keep: IntRange,
        protectedFiles: kotlin.collections.Collection<File>,
        maxBytes: Long = MAX_BYTES,
    ): Long {
        val protectedPaths = protectedFiles.map { it.absolutePath }.toSet()
        val index = loadIndex()
        var freed = 0L

        // 旧版本缓存没有归属记录；重新登记或按窗口外处理，这里直接清掉，避免无限增长。
        root.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.contains(".part") &&
                file.name != INDEX_FILE && file.name !in index &&
                file.absolutePath !in protectedPaths
            ) {
                freed += delete(file)
            }
        }

        for ((name, entry) in index.toList()) {
            if (entry.bookId == bookId && entry.chapterIndex !in keep) {
                val file = File(root, name)
                if (file.absolutePath !in protectedPaths) {
                    freed += delete(file)
                    index.remove(name)
                }
            }
        }
        saveIndex(index)
        return freed + evictExcept(protectedFiles, maxBytes)
    }

    /**
     * 总量超出上限时按最旧优先淘汰，返回释放的字节数。
     * 调用方通常传当前窗口内的文件，防止删掉马上要播的音频。
     */
    fun evictExcept(
        protectedFiles: kotlin.collections.Collection<File>,
        maxBytes: Long = MAX_BYTES,
    ): Long {
        var total = sizeBytes()
        if (total <= maxBytes) return 0L
        val protectedPaths = protectedFiles.map { it.absolutePath }.toSet()
        val index = loadIndex()
        var freed = 0L
        val candidates = audioFiles()
            .filter { it.absolutePath !in protectedPaths }
            .sortedBy { it.lastModified() }
        for (file in candidates) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                index.remove(file.name)
                total -= size
                freed += size
            }
        }
        if (freed > 0L) saveIndex(index)
        return freed
    }

    private fun audioFiles(): List<File> = runCatching {
        root.listFiles()?.filter {
            it.isFile && !it.name.contains(".part") && it.name != INDEX_FILE
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun delete(file: File): Long {
        val size = file.length()
        return if (file.delete()) size else 0L
    }

    private fun indexFile() = File(root, INDEX_FILE)

    private fun loadIndex(): MutableMap<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        runCatching {
            val file = indexFile()
            if (!file.isFile) return@runCatching
            val obj = JSONObject(file.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val value = obj.optJSONObject(name) ?: continue
                result[name] = Entry(
                    bookId = value.optString("bookId"),
                    chapterIndex = value.optInt("index", -1),
                    savedAt = value.optLong("savedAt", 0L),
                )
            }
        }.onFailure { Log.w(TAG, "读媒体缓存索引失败: ${it.message}") }
        return result
    }

    private fun saveIndex(index: Map<String, Entry>) {
        runCatching {
            root.mkdirs()
            val obj = JSONObject()
            for ((name, entry) in index) {
                obj.put(name, JSONObject()
                    .put("bookId", entry.bookId)
                    .put("index", entry.chapterIndex)
                    .put("savedAt", entry.savedAt))
            }
            val target = indexFile()
            val tmp = File(root, "$INDEX_FILE.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { Log.w(TAG, "写媒体缓存索引失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "TingshuMedia"
        private const val INDEX_FILE = "index.json"

        /** 默认最多保留 500MB，超出上限时按最旧淘汰。 */
        const val MAX_BYTES = 500L * 1024L * 1024L

        private fun tag(base: String) = Integer.toHexString(base.hashCode())

        private fun hex(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val chars = "0123456789abcdef"
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                out.append(chars[v shr 4]).append(chars[v and 0x0f])
            }
            return out.toString()
        }

        private fun extensionOf(trackPath: String): String {
            val raw = trackPath.substringAfterLast('.', "")
            val ext = raw.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
            return ext.take(8).ifEmpty { "audio" }
        }
    }
}
