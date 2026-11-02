package com.tingshu.app.data

/**
 * 与服务端网页 app.js 里的 hexOf()/unhex() 完全一致：
 * 书籍 id（例如 `net:三体`）按 UTF-8 转成小写十六进制，作为进度/元数据的键。
 */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return runCatching { String(out, Charsets.UTF_8) }.getOrNull()
    }
}
