package com.tingshu.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 听书服务端（uhttpd + CGI）的 HTTP 客户端。
 *
 * 接口约定来自 openwrt/files/usr/lib/tingshu/api 下的几个 CGI 脚本：
 *  - GET  library.json                     书库索引
 *  - GET  cgi-ts/progress                  全部进度
 *  - POST cgi-ts/progress  (form)          写一本进度
 *  - GET  cgi-ts/meta                      书名/简介/封面
 *  - GET  cgi-ts/collections               合集
 *  - POST cgi-ts/manage?action=rescan      重扫书库
 */
class TingshuApi {

    data class RawLibrary(val root: String, val error: String?, val books: List<RawBook>)
    data class RawBook(val id: String, val title: String, val cover: String?, val tracks: List<RawTrack>)
    data class RawTrack(val title: String, val path: String)

    suspend fun probe(base: String): Boolean = withContext(Dispatchers.IO) {
        val text = request("${base}library.json?t=${System.currentTimeMillis()}", timeoutMs = 5000)
        JSONObject(text).has("books")
    }

    suspend fun fetchLibrary(base: String): RawLibrary = withContext(Dispatchers.IO) {
        val text = request("${base}library.json?t=${System.currentTimeMillis()}")
        val json = JSONObject(text)

        val arr = json.optJSONArray("books") ?: JSONArray()
        val books = ArrayList<RawBook>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("title") }
            if (id.isBlank()) continue

            val trackArr = o.optJSONArray("tracks") ?: JSONArray()
            val tracks = ArrayList<RawTrack>(trackArr.length())
            for (k in 0 until trackArr.length()) {
                val t = trackArr.optJSONObject(k) ?: continue
                val path = t.optString("path")
                if (path.isBlank()) continue
                tracks.add(RawTrack(t.optString("title").ifBlank { path.substringAfterLast('/') }, path))
            }
            if (tracks.isEmpty()) continue

            books.add(
                RawBook(
                    id = id,
                    title = o.optString("title").ifBlank { id },
                    cover = o.optString("cover").ifBlank { null },
                    tracks = tracks,
                )
            )
        }

        RawLibrary(
            root = json.optString("root"),
            error = json.optString("error").ifBlank { null },
            books = books,
        )
    }

    suspend fun fetchMeta(base: String): Map<String, MetaRecord> = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/meta?t=${System.currentTimeMillis()}", timeoutMs = 20000)
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext emptyMap()

        val items = json.optJSONObject("items") ?: JSONObject()
        val out = HashMap<String, MetaRecord>(items.length())
        val keys = items.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = items.optJSONObject(key) ?: continue
            val palette = if (o.isNull("palette") || !o.has("palette")) null else o.optInt("palette")
            out[key] = MetaRecord(
                title = o.optString("title"),
                description = o.optString("desc"),
                palette = palette,
                coverBase64 = o.optString("cover").ifBlank { null },
                coverType = o.optString("coverType").ifBlank { null },
                updatedAt = o.optLong("updatedAt"),
            )
        }
        out
    }

    suspend fun fetchProgress(base: String): Map<String, RemoteProgress> = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/progress?t=${System.currentTimeMillis()}")
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext emptyMap()

        val items = json.optJSONObject("items") ?: JSONObject()
        val out = HashMap<String, RemoteProgress>(items.length())
        val keys = items.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = items.optJSONObject(key) ?: continue
            val done = ArrayList<Int>()
            val arr = o.optJSONArray("done")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optInt(i, -1)
                    if (n >= 0) done.add(n)
                }
            }
            out[key] = RemoteProgress(
                index = o.optInt("index", 0),
                time = o.optDouble("time", 0.0),
                done = done,
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
        out
    }

    suspend fun fetchCollections(base: String): List<Collection> = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/collections?t=${System.currentTimeMillis()}")
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext emptyList()
        val arr = json.opt("data") as? JSONArray ?: return@withContext emptyList()

        val out = ArrayList<Collection>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val ids = ArrayList<String>()
            val bookIds = o.optJSONArray("bookIds")
            if (bookIds != null) {
                for (k in 0 until bookIds.length()) {
                    val v = bookIds.optString(k)
                    if (v.isNotBlank()) ids.add(v)
                }
            }
            out.add(
                Collection(
                    id = id,
                    name = o.optString("name").ifBlank { "未命名合集" },
                    bookIds = ids,
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                )
            )
        }
        out
    }

    suspend fun pushCollections(base: String, list: List<Collection>): Boolean =
        withContext(Dispatchers.IO) {
            val arr = CollectionsStore.toJson(list)
            val body = "c=" + Urls.encodeSegment(arr.toString())
            val text = request(
                url = base + "cgi-ts/collections",
                method = "POST",
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 12000,
            )
            JSONObject(text).optBoolean("ok")
        }

    suspend fun fetchPauseMarks(base: String): List<PauseMark> = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/pausemarks?t=${System.currentTimeMillis()}")
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext emptyList()
        val arr = json.optJSONArray("items") ?: return@withContext emptyList()
        val out = ArrayList<PauseMark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val bookId = o.optString("bookId")
            if (id.isBlank() || bookId.isBlank()) continue
            out.add(
                PauseMark(
                    id = id,
                    bookId = bookId,
                    bookTitle = o.optString("bookTitle"),
                    index = o.optInt("index"),
                    trackTitle = o.optString("trackTitle"),
                    time = o.optDouble("time", 0.0),
                    reason = o.optString("reason").ifBlank { "pause" },
                    createdAt = o.optLong("createdAt"),
                )
            )
        }
        out
    }

    suspend fun pushPauseMarks(base: String, list: List<PauseMark>): Boolean =
        withContext(Dispatchers.IO) {
            val body = PauseMarksStore.toJson(list).toString()
            val text = request(
                url = base + "cgi-ts/pausemarks",
                method = "POST",
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/json",
                timeoutMs = 12000,
            )
            JSONObject(text).optBoolean("ok")
        }

    suspend fun pushMeta(
        base: String,
        bookId: String,
        title: String,
        description: String,
        palette: Int?,
        coverBytes: ByteArray?,
        coverType: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val cover = if (coverBytes != null && coverBytes.isNotEmpty()) {
            // java.util.Base64 在 Android 8.0+ 可用，也方便在 JVM 单元测试里跑
            java.util.Base64.getEncoder().encodeToString(coverBytes)
        } else {
            ""
        }
        val payload = JSONObject()
            .put("id", Hex.encode(bookId))
            .put("title", title)
            .put("desc", description)
            .put("palette", palette ?: JSONObject.NULL)
            .put("cover", cover)
            .put("coverType", if (cover.isNotEmpty()) coverType.orEmpty() else "")
            .put("updatedAt", System.currentTimeMillis())

        val text = request(
            url = base + "cgi-ts/meta",
            method = "POST",
            body = payload.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = 20000,
        )
        JSONObject(text).optBoolean("ok")
    }

    /** 上传单个文件到书籍目录下的相对路径。 */
    suspend fun uploadFile(base: String, relPath: String, file: File): Long? =
        withContext(Dispatchers.IO) {
            val url = base + "cgi-ts/manage?action=upload&p=" + Urls.encodePath(relPath)
            val text = uploadStream(url, file, mimeOf(relPath))
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) null else json.optLong("size", file.length())
        }

    /** 把压缩包整个交给服务端解压（zip/tar.gz），返回解出的项目数。 */
    suspend fun uploadArchive(base: String, fileName: String, file: File): Int = withContext(Dispatchers.IO) {
        val url = base + "cgi-ts/manage?action=archive&n=" + Urls.encodeSegment(fileName)
        val text = uploadStream(url, file, "application/octet-stream", timeoutMs = 300000)
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "解压失败"))
        json.optInt("items", 0)
    }

    suspend fun deleteBook(base: String, folder: String): Boolean = withContext(Dispatchers.IO) {
        val url = base + "cgi-ts/manage?action=delete&b=" + Urls.encodeSegment(folder)
        val text = request(url = url, method = "POST", timeoutMs = 20000)
        JSONObject(text).optBoolean("ok")
    }

    suspend fun pushProgress(base: String, bookId: String, p: Progress): Boolean =
        withContext(Dispatchers.IO) {
            val body = "b=" + Hex.encode(bookId) +
                "&i=" + p.index.coerceAtLeast(0) +
                "&t=" + String.format(Locale.US, "%.2f", p.time.coerceAtLeast(0.0)) +
                "&d=" + p.done.filter { it >= 0 }.joinToString(",") +
                "&u=" + p.updatedAt
            val text = request(
                url = base + "cgi-ts/progress",
                method = "POST",
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 8000,
            )
            JSONObject(text).optBoolean("ok")
        }

    /** 触发服务端重新扫描书库，返回 (书数, 章节数)。 */
    suspend fun rescan(base: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val text = request(
            url = base + "cgi-ts/manage?action=rescan",
            method = "POST",
            body = ByteArray(0),
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = 20000,
        )
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) throw IOException(json.optString("error", "重新扫描失败"))
        json.optInt("books", 0) to json.optInt("tracks", 0)
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 12000,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs * 2
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json, */*")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
            }

            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code ${text.take(120)}".trim())
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 从本地文件流式上传，避免把几十 MB 音频整个读进内存。 */
    private fun uploadStream(
        url: String,
        file: File,
        contentType: String,
        timeoutMs: Int = 120000,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code ${text.take(120)}".trim())
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        "ape" -> "audio/ape"
        "wma" -> "audio/x-ms-wma"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
