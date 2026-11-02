package com.tingshu.app.data

/**
 * 服务端地址与音频地址。
 *
 * 服务端接口都是相对路径（library.json、media/...、cgi-ts/...），所以只需要一个
 * 归一化的 base（永远以 `/` 结尾）。
 */
object Urls {

    private const val DEFAULT_PORT = 8686

    /** 把用户输入的 `192.168.1.1`、`192.168.1.1:8686`、`http://x/` 统一成 `http://x:8686/`。 */
    fun normalizeServer(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')

        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = s.substring(0, schemeEnd + 3).lowercase()
        val rest = s.substring(schemeEnd + 3)
        if (rest.isEmpty()) return null

        val slash = rest.indexOf('/')
        val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        if (hostPort.isBlank()) return null

        val colonCount = hostPort.count { it == ':' }
        val hasPort = colonCount == 1 && hostPort.substringAfterLast(':').toIntOrNull() != null
        val withPort = if (hasPort) hostPort else "$hostPort:$DEFAULT_PORT"

        val normalized = "$scheme$withPort$path/"
        return try {
            val uri = java.net.URI(normalized)
            if (uri.host.isNullOrBlank()) null else normalized
        } catch (t: Throwable) {
            null
        }
    }

    fun media(base: String, path: String): String {
        return base + "media/" + encodePath(path)
    }

    /** 相对路径逐段编码，保留 `/` 分隔符。 */
    fun encodePath(path: String): String {
        return path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
    }

    /** 等价于 JS 的 encodeURIComponent。 */
    fun encodeSegment(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '!' ||
                ch == '~' || ch == '*' || ch == '\'' || ch == '(' || ch == ')'
            ) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    /** `http://192.168.1.1:8686/` → `192.168.1.1:8686`，用于界面上显示。 */
    fun hostLabel(base: String): String {
        if (base.isBlank()) return ""
        val s = base.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return s.ifEmpty { base }
    }

    private const val HEX = "0123456789ABCDEF"
}
