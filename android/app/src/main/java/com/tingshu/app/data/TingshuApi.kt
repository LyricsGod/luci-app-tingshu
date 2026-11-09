package com.tingshu.app.data

import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
class TingshuApi(
    private val tokenProvider: () -> String? = { null },
    /** 传了就启用本地缓存：请求成功写盘、网络失败回退、cacheOnly 只读盘。 */
    private val cache: CacheStore? = null,
) {

    /**
     * 统一的取数入口：
     *  - cacheOnly = true：只读本地缓存，没有就返回 null（用于「先渲染旧数据」）
     *  - 正常请求：成功写缓存；失败且有缓存就用缓存兜底，至少不白屏
     */
    private suspend fun loadText(key: String, cacheOnly: Boolean, block: suspend () -> String): String? {
        if (cacheOnly) return cache?.read(key)
        return try {
            block().also { cache?.write(key, it) }
        } catch (t: Throwable) {
            val cached = cache?.read(key)
            if (cached == null) throw t
            Log.w(TAG, "网络失败，改用缓存兜底: $key (${t.javaClass.simpleName})")
            cached
        }
    }

    private companion object {
        const val TAG = "TingshuApi"
    }

    class ApiException(val code: Int, message: String) : IOException(message)

    data class RawLibrary(val root: String, val error: String?, val books: List<RawBook>)
    data class RawBook(val id: String, val title: String, val cover: String?, val tracks: List<RawTrack>)
    data class RawTrack(val title: String, val path: String)

    data class AuthStatus(
        val configured: Boolean,
        val authenticated: Boolean,
        val user: String,
        val role: String,
    )

    data class AuthSession(val token: String, val user: String, val role: String)
    data class UserInfo(val user: String, val role: String)

    suspend fun authStatus(base: String): AuthStatus = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/auth?action=status&t=${System.currentTimeMillis()}", timeoutMs = 6000)
        val o = JSONObject(text)
        AuthStatus(
            configured = o.optBoolean("configured") || o.optInt("configured", 0) == 1,
            authenticated = o.optBoolean("authenticated") || o.optInt("authenticated", 0) == 1,
            user = o.optString("user"),
            role = o.optString("role"),
        )
    }

    suspend fun auth(base: String, action: String, user: String, password: String): AuthSession =
        withContext(Dispatchers.IO) {
            val text = request(
                url = "${base}cgi-ts/auth?action=$action",
                method = "POST",
                body = formBody("u" to user, "p" to password).toByteArray(),
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 12000,
            )
            val o = JSONObject(text)
            if (!o.optBoolean("ok")) throw IOException(o.optString("error", "登录失败"))
            AuthSession(o.optString("token"), o.optString("user"), o.optString("role"))
        }

    suspend fun logout(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            request("${base}cgi-ts/auth?action=logout", method = "POST", body = ByteArray(0),
                contentType = "application/x-www-form-urlencoded", timeoutMs = 6000)
            true
        }.getOrDefault(false)
    }

    suspend fun fetchLayoutColumns(base: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val text = request("${base}cgi-ts/layout?t=${System.currentTimeMillis()}", timeoutMs = 6000)
            val o = JSONObject(text)
            if (o.optBoolean("ok")) o.optJSONObject("layout")?.optInt("columns", 0) else null
        }.getOrNull()
    }

    suspend fun pushLayoutColumns(base: String, columns: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("columns", columns).toString().toByteArray()
            request(
                url = "${base}cgi-ts/layout",
                method = "POST",
                body = body,
                contentType = "application/json",
                timeoutMs = 8000,
            )
            true
        }.getOrDefault(false)
    }

    suspend fun listUsers(base: String): List<UserInfo> = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/users?t=${System.currentTimeMillis()}", timeoutMs = 8000)
        val o = JSONObject(text)
        if (!o.optBoolean("ok")) throw IOException(o.optString("error", "加载用户失败"))
        val arr = o.optJSONArray("users") ?: JSONArray()
        val out = ArrayList<UserInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val name = u.optString("user")
            if (name.isBlank()) continue
            out.add(UserInfo(name, u.optString("role", "user")))
        }
        out
    }

    suspend fun createUser(base: String, user: String, password: String, role: String): Boolean =
        userAction(base, "create", "u" to user, "p" to password, "role" to role)

    suspend fun resetPassword(base: String, user: String, password: String): Boolean =
        userAction(base, "reset", "u" to user, "p" to password)

    suspend fun deleteUser(base: String, user: String): Boolean =
        userAction(base, "delete", "u" to user)

    private suspend fun userAction(base: String, action: String, vararg fields: Pair<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val text = request(
                url = "${base}cgi-ts/users?action=$action",
                method = "POST",
                body = formBody(*fields).toByteArray(),
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 10000,
            )
            JSONObject(text).optBoolean("ok")
        }

    private fun formBody(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    suspend fun probe(base: String): Boolean = withContext(Dispatchers.IO) {
        val text = request("${base}library.json?t=${System.currentTimeMillis()}", timeoutMs = 5000)
        JSONObject(text).has("books")
    }

    suspend fun fetchLibrary(base: String, cacheOnly: Boolean = false): RawLibrary? = withContext(Dispatchers.IO) {
        val text = loadText(CacheStore.libraryKey(base), cacheOnly) {
            request("${base}library.json?t=${System.currentTimeMillis()}")
        } ?: return@withContext null
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

    suspend fun fetchMeta(base: String, cacheOnly: Boolean = false): Map<String, MetaRecord> = withContext(Dispatchers.IO) {
        val text = loadText(CacheStore.metaKey(base), cacheOnly) {
            request("${base}cgi-ts/meta?t=${System.currentTimeMillis()}", timeoutMs = 20000)
        } ?: return@withContext emptyMap()
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

    /**
     * 每章时长（秒），键是 track.path。
     * 服务端用 duration.pl 读文件头算出来，客户端拿去算「已听 X 小时 / 全书 Y 小时」。
     */
    suspend fun fetchDurations(
        base: String,
        bookRel: String,
        cacheOnly: Boolean = false,
    ): Map<String, Double> = withContext(Dispatchers.IO) {
        val key = CacheStore.durationsKey(base, Hex.encode("net:$bookRel"))
        val text = loadText(key, cacheOnly) {
            request(
                "${base}cgi-ts/durations?b=" + URLEncoder.encode(bookRel, "UTF-8") +
                    "&t=" + System.currentTimeMillis(),
                timeoutMs = 30000,
            )
        } ?: return@withContext emptyMap()
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext emptyMap()
        val items = json.optJSONObject("items") ?: return@withContext emptyMap()
        val out = HashMap<String, Double>(items.length())
        val keys = items.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = items.optDouble(k, 0.0)
            if (v > 0) out[k] = v
        }
        out
    }

    suspend fun fetchCollections(base: String, cacheOnly: Boolean = false): List<Collection> = withContext(Dispatchers.IO) {
        val text = loadText(CacheStore.collectionsKey(base), cacheOnly) {
            request("${base}cgi-ts/collections?t=${System.currentTimeMillis()}")
        } ?: return@withContext emptyList()
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

    /** 拉暂停记录：返回记录本身 + 服务器上的删除墓碑（id → 删除时间）。 */
    suspend fun fetchPauseMarks(base: String): PauseMarksSnapshot = withContext(Dispatchers.IO) {
        val text = request("${base}cgi-ts/pausemarks?t=${System.currentTimeMillis()}")
        val json = JSONObject(text)
        if (!json.optBoolean("ok")) return@withContext PauseMarksSnapshot()
        val arr = json.optJSONArray("items") ?: JSONArray()
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
                    source = o.optString("source"),
                )
            )
        }
        val removed = HashMap<String, Long>()
        json.optJSONObject("removed")?.let { obj ->
            obj.keys().forEach { key -> removed[key] = obj.optLong(key) }
        }
        PauseMarksSnapshot(out, removed)
    }

    /** 推送：完整记录 + 删除墓碑，服务端整体写回。 */
    suspend fun pushPauseMarks(
        base: String,
        list: List<PauseMark>,
        removed: Map<String, Long> = emptyMap(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("items", PauseMarksStore.toJson(list))
                .put("removed", JSONObject().apply { removed.forEach { (id, ts) -> put(id, ts) } })
            val text = request(
                url = base + "cgi-ts/pausemarks",
                method = "POST",
                body = payload.toString().toByteArray(Charsets.UTF_8),
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

    /** 建连接：统一超时、禁用缓存、带上会话 token。 */
    private fun openConnection(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            conn.setRequestProperty("Cookie", "tingshu_session=$token")
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    /** 读响应；非 2xx 抛 [ApiException]，错误正文截断后带在消息里。 */
    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw ApiException(code, "HTTP $code ${text.take(120)}".trim())
        }
        return text
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 12000,
    ): String {
        val startedAt = System.nanoTime()
        val conn = openConnection(url, method, timeoutMs, timeoutMs * 2)
        try {
            conn.setRequestProperty("Accept", "application/json, */*")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val text = readResponse(conn)
            logDone(method, url, text.length, startedAt)
            return text
        } catch (t: Throwable) {
            Log.w(TAG, "$method ${shortUrl(url)} 失败(${elapsedMs(startedAt)}ms): ${t.javaClass.simpleName} ${t.message}")
            throw t
        } finally {
            conn.disconnect()
        }
    }

    private fun logDone(method: String, url: String, bytes: Int, startedAt: Long) {
        Log.d(TAG, "$method ${shortUrl(url)} -> ${bytes}B, ${elapsedMs(startedAt)}ms")
    }

    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    /** 日志里只保留末段路径，避免把 base 和 hex id 全打出来 */
    private fun shortUrl(url: String): String = url.substringAfter("://", url).substringAfter('/', "")

    /* ---------------- 服务器备份 / 恢复（仅管理员） ---------------- */

    data class BackupInfo(
        val users: List<String>,
        val booksDir: String,
        val freeKb: Long,
        val sizes: Map<String, Long>,
    )

    data class BackupDownload(val url: String, val fileName: String, val headers: Map<String, String>)

    private fun backupUrl(base: String, action: String, mode: String, user: String): String {
        var q = "${base}cgi-ts/backup?action=$action&mode=${URLEncoder.encode(mode, "UTF-8")}"
        if (mode == "user" && user.isNotBlank()) {
            q += "&u=${URLEncoder.encode(user, "UTF-8")}"
        }
        return q
    }

    suspend fun backupInfo(base: String, mode: String, user: String): BackupInfo =
        withContext(Dispatchers.IO) {
            val o = JSONObject(request(backupUrl(base, "info", mode, user), timeoutMs = 15000))
            if (!o.optBoolean("ok")) throw IOException(o.optString("error", "读取备份信息失败"))
            val arr = o.optJSONArray("users") ?: JSONArray()
            val users = (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            val sz = o.optJSONObject("sizes") ?: JSONObject()
            val sizes = HashMap<String, Long>()
            sz.keys().forEach { sizes[it] = sz.optLong(it) }
            BackupInfo(users, o.optString("booksDir"), o.optLong("freeKb"), sizes)
        }

    /** 交给系统 DownloadManager 下载，35GB 也不会占 App 内存。 */
    fun backupDownload(base: String, mode: String, user: String): BackupDownload {
        val headers = HashMap<String, String>()
        tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            headers["Cookie"] = "tingshu_session=$it"
            headers["Authorization"] = "Bearer $it"
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(java.util.Date())
        return BackupDownload(backupUrl(base, "export", mode, user), "tingshu-$mode-$stamp.tar.gz", headers)
    }

    /** 流式上传备份包并恢复；不会把整个文件读进内存。 */
    suspend fun restoreBackup(
        base: String,
        mode: String,
        user: String,
        size: Long,
        open: () -> java.io.InputStream?,
    ): Int = withContext(Dispatchers.IO) {
        val conn = openConnection(backupUrl(base, "import", mode, user), "POST", 10000, 30 * 60 * 1000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/gzip")
            if (size > 0) conn.setFixedLengthStreamingMode(size) else conn.setChunkedStreamingMode(64 * 1024)
            val input = open() ?: throw IOException("打不开备份文件")
            input.use { src -> conn.outputStream.use { dst -> src.copyTo(dst, 64 * 1024) } }
            val o = JSONObject(readResponse(conn))
            if (!o.optBoolean("ok")) throw IOException(o.optString("error", "恢复失败"))
            o.optInt("entries", 0)
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
        val conn = openConnection(url, "POST", 10000, timeoutMs)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            return readResponse(conn)
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
