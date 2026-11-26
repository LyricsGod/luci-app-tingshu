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
        val target = File(dir, "$hex.img")
        // meta 每次刷新都会把封面 base64 原样带回来。旧实现每来一次就整段解码+
        // 比对文件大小，9 本书就是 9 次大字符串解码；用指纹先挡掉没变的封面。
        val fp = Integer.toHexString(base64.hashCode())
        if (target.isFile && fingerprintOf(hex) == fp) return target
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val saved = saveRaw(hex, bytes)
            if (saved != null) File(dir, "$hex.fp").writeText(fp)
            saved
        } catch (t: Throwable) {
            null
        }
    }

    private fun fingerprintOf(hex: String): String? =
        runCatching { File(dir, "$hex.fp").readText().takeIf { it.isNotBlank() } }.getOrNull()

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
        runCatching { File(dir, "$hex.fp").delete() }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
