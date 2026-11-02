package com.tingshu.app.data

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * 服务端把自定义封面以 base64 存在 meta JSON 里（见 cgi-ts/meta）。
 * 这里解码后写进 App 私有目录，交给 Coil 当普通文件加载，避免反复解码大字符串。
 */
class CoverStore(context: Context) {

    private val dir = File(context.filesDir, "covers")

    fun save(hex: String, base64: String): File? {
        if (base64.length < 32) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            saveRaw(hex, bytes)
        } catch (t: Throwable) {
            null
        }
    }

    fun saveRaw(hex: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val target = File(dir, "$hex.img")
            if (target.isFile && target.length() == bytes.size.toLong()) return target

            val tmp = File(dir, "$hex.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            target
        } catch (t: Throwable) {
            null
        }
    }

    fun delete(hex: String) {
        runCatching { File(dir, "$hex.img").delete() }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
