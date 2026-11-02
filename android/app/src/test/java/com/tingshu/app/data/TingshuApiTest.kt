package com.tingshu.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * 用本地 HTTP 服务模拟 uhttpd 的返回，验证客户端解析和请求体与服务端 CGI 完全对得上。
 *
 * 这里手写一个极简 HTTP/1.1 服务，避免测试依赖 JDK 的 jdk.httpserver 模块。
 */
class TingshuApiTest {

    private lateinit var server: TinyHttpServer
    private lateinit var base: String
    private val captured = ConcurrentHashMap<String, String>()
    private val api = TingshuApi()

    @Before
    fun setUp() {
        server = TinyHttpServer { method, path, body ->
            captured["$method $path"] = body
            when {
                path.endsWith("/library.json") -> LIBRARY
                path.endsWith("/cgi-ts/meta") ->
                    if (method == "POST") """{"ok":true}""" else META
                path.endsWith("/cgi-ts/progress") ->
                    if (method == "POST") """{"ok":true}""" else PROGRESS
                path.endsWith("/cgi-ts/collections") ->
                    if (method == "POST") """{"ok":true}""" else COLLECTIONS
                path.endsWith("/cgi-ts/pausemarks") ->
                    if (method == "POST") """{"ok":true}""" else PAUSEMARKS
                path.contains("/cgi-ts/manage") -> """{"ok":true,"books":3,"tracks":10}"""
                else -> """{"ok":false}"""
            }
        }
        base = "http://127.0.0.1:${server.port}/"
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `书库索引能解析出中文书名和章节`() = runBlocking {
        val library = api.fetchLibrary(base)
        assertEquals("/mnt/audiobooks", library.root)
        assertNull(library.error)
        assertEquals(1, library.books.size)

        val book = library.books.first()
        assertEquals("三体", book.id)
        assertEquals("三体", book.title)
        assertEquals("三体/cover.jpg", book.cover)
        assertEquals(2, book.tracks.size)
        assertEquals("三体/01 地球往事.mp3", book.tracks[0].path)
    }

    @Test
    fun `meta 用书籍 id 的十六进制做键`() = runBlocking {
        val meta = api.fetchMeta(base)
        val record = meta[Hex.encode("net:三体")]
        assertNotNull(record)
        assertEquals("三体（典藏版）", record!!.title)
        assertEquals(3, record.palette)
        assertEquals("aGVsbG8=", record.coverBase64)
    }

    @Test
    fun `进度接口解析 index time done`() = runBlocking {
        val progress = api.fetchProgress(base)
        val record = progress[Hex.encode("net:三体")]
        assertNotNull(record)
        assertEquals(1, record!!.index)
        assertEquals(123.45, record.time, 0.001)
        assertEquals(listOf(0), record.done)
        assertEquals(1700000000000L, record.updatedAt)
    }

    @Test
    fun `推送进度使用服务端要的 form 字段`() = runBlocking {
        val record = Progress(index = 2, time = 12.3, done = listOf(0, 1), updatedAt = 1700000000123L)
        assertTrue(api.pushProgress(base, "net:三体", record))

        assertEquals(
            "b=6e65743ae4b889e4bd93&i=2&t=12.30&d=0,1&u=1700000000123",
            captured["POST /cgi-ts/progress"],
        )
    }

    @Test
    fun `合集接口解析 data 数组`() = runBlocking {
        val collections = api.fetchCollections(base)
        assertEquals(1, collections.size)
        assertEquals("科幻", collections[0].name)
        assertEquals(listOf("net:三体"), collections[0].bookIds)
    }

    @Test
    fun `重新扫描返回书数与章节数`() = runBlocking {
        assertEquals(3 to 10, api.rescan(base))
    }

    @Test
    fun `probe 能识别听书服务`() = runBlocking {
        assertTrue(api.probe(base))
    }

    @Test
    fun `推送合集使用 c= 表单字段`() = runBlocking {
        val list = listOf(
            Collection("c1", "科幻", listOf("net:三体"), 1L, 2L)
        )
        assertTrue(api.pushCollections(base, list))
        val body = captured["POST /cgi-ts/collections"].orEmpty()
        assertTrue(body.startsWith("c="))
        val decoded = java.net.URLDecoder.decode(body.substring(2), "UTF-8")
        val arr = org.json.JSONArray(decoded)
        assertEquals(1, arr.length())
        assertEquals("科幻", arr.getJSONObject(0).getString("name"))
        assertEquals("net:三体", arr.getJSONObject(0).getJSONArray("bookIds").getString(0))
    }

    @Test
    fun `推送暂停记录使用 JSON 数组`() = runBlocking {
        val mark = PauseMark("pm-1", "net:三体", "三体", 2, "红岸之谜", 12.5, "pause", 1000L)
        assertTrue(api.pushPauseMarks(base, listOf(mark)))
        val body = captured["POST /cgi-ts/pausemarks"].orEmpty()
        val arr = org.json.JSONArray(body)
        assertEquals(1, arr.length())
        assertEquals("pm-1", arr.getJSONObject(0).getString("id"))
        assertEquals("pause", arr.getJSONObject(0).getString("reason"))
    }

    @Test
    fun `拉取暂停记录`() = runBlocking {
        val marks = api.fetchPauseMarks(base)
        assertEquals(1, marks.size)
        assertEquals("pm-1", marks[0].id)
        assertEquals("net:三体", marks[0].bookId)
    }

    @Test
    fun `推送 meta 带 hex id 和 base64 封面`() = runBlocking {
        assertTrue(
            api.pushMeta(
                base = base,
                bookId = "net:三体",
                title = "三体（典藏版）",
                description = "文革如火如荼",
                palette = 3,
                coverBytes = "hello".toByteArray(),
                coverType = "image/jpeg",
            )
        )
        val body = captured["POST /cgi-ts/meta"].orEmpty()
        val json = org.json.JSONObject(body)
        assertEquals("6e65743ae4b889e4bd93", json.getString("id"))
        assertEquals("三体（典藏版）", json.getString("title"))
        assertEquals(3, json.getInt("palette"))
        assertEquals("aGVsbG8=", json.getString("cover"))
        assertEquals("image/jpeg", json.getString("coverType"))
    }

    @Test
    fun `上传文件走 manage upload`() = runBlocking {
        val temp = java.io.File.createTempFile("tingshu-up", ".mp3")
        temp.writeText("hello-audio")
        try {
            assertEquals(temp.length(), api.uploadFile(base, "三体/01 地球往事.mp3", temp))
            assertEquals("hello-audio", captured["POST /cgi-ts/manage"])
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `删除书籍`() = runBlocking {
        assertTrue(api.deleteBook(base, "三体"))
    }

    private companion object {
        const val LIBRARY = """
            {"generated":1700000000,"root":"/mnt/audiobooks","books":[
              {"id":"三体","title":"三体","cover":"三体/cover.jpg","tracks":[
                {"title":"01 地球往事","path":"三体/01 地球往事.mp3"},
                {"title":"02 红岸之谜","path":"三体/02 红岸之谜.mp3"}
              ]}
            ]}
        """

        const val META = """
            {"ok":true,"items":{"6e65743ae4b889e4bd93":{
              "id":"6e65743ae4b889e4bd93","title":"三体（典藏版）","desc":"文化大革命如火如荼",
              "palette":3,"cover":"aGVsbG8=","coverType":"image/png","updatedAt":1700000000000
            }}}
        """

        const val PROGRESS = """
            {"ok":true,"items":{"6e65743ae4b889e4bd93":{
              "index":1,"time":123.45,"done":[0],"updatedAt":1700000000000
            }}}
        """

        const val COLLECTIONS = """
            {"ok":true,"data":[{"id":"c1","name":"科幻","bookIds":["net:三体"],"createdAt":1,"updatedAt":2}]}
        """

        const val PAUSEMARKS = """
            {"ok":true,"items":[{"id":"pm-1","bookId":"net:三体","bookTitle":"三体","index":2,
              "trackTitle":"红岸之谜","time":12.5,"reason":"pause","createdAt":1000}]}
        """
    }
}

/** 只支持一个请求一个连接的极简 HTTP 服务，够测试用。 */
private class TinyHttpServer(
    private val handler: (method: String, path: String, body: String) -> String,
) : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 3000
                    val input = client.getInputStream().buffered()
                    val requestLine = readLine(input) ?: return@use
                    val parts = requestLine.split(" ")
                    val method = parts.getOrElse(0) { "GET" }
                    val path = parts.getOrElse(1) { "/" }.substringBefore('?')

                    var contentLength = 0
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }

                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }

                    val payload = handler(method, path, String(bodyBytes, 0, read, Charsets.UTF_8))
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    client.getOutputStream().apply {
                        write(header.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            } catch (t: Throwable) {
                if (socket.isClosed) break
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
