package com.tingshu.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 手机端自己解 zip / tar.gz：路由器上的 unzip 会把中文名转成 GBK，
 * 所以和网页端一样，逐个文件走 manage?action=upload 上传。
 */
class ArchiveImporter(private val context: Context) {

    suspend fun import(
        uri: Uri,
        displayName: String,
        onEntry: suspend (relPath: String, file: File) -> Boolean,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val lower = displayName.lowercase()
        val ext = when {
            lower.endsWith(".zip") -> "zip"
            lower.endsWith(".tar.gz") -> "tar.gz"
            lower.endsWith(".tgz") -> "tgz"
            lower.endsWith(".tar") -> "tar"
            else -> throw IllegalArgumentException("只支持 zip / tar.gz / tgz")
        }

        val cacheFile = File(context.cacheDir, "import-${System.currentTimeMillis()}.$ext")
        try {
            copyToCache(uri, cacheFile)
            if (context.cacheDir.usableSpace < MIN_FREE_BYTES) {
                throw IOException("手机缓存空间不足")
            }
            when (ext) {
                "zip" -> importZip(cacheFile, onEntry, onProgress)
                else -> importTar(cacheFile, onEntry, onProgress)
            }
        } finally {
            cacheFile.delete()
        }
    }

    private suspend fun importZip(
        file: File,
        onEntry: suspend (String, File) -> Boolean,
        onProgress: (Int, Int, String) -> Unit,
    ): Pair<Int, Int> {
        val zip = openZip(file)
        try {
            val pairs = zip.entries.asSequence()
                .filter { !it.isDirectory }
                .mapNotNull { e -> normalizeEntryPath(e.name)?.let { name -> name to e } }
                .toList()
            val stripped = stripWrapperRoot(pairs.map { it.first })
            val jobs = pairs.mapIndexedNotNull { i, (name, entry) ->
                val rel = stripped.getOrElse(i) { name }
                if (isUploadJob(rel)) rel to entry else null
            }
            if (jobs.isEmpty()) throw IOException("压缩包里没有按书分目录的音频")

            var imported = 0
            var failed = 0
            for ((index, job) in jobs.withIndex()) {
                coroutineContext.ensureActive()
                val (rel, entry) = job
                val temp = File(context.cacheDir, "upload-${System.nanoTime()}")
                try {
                    zip.getInputStream(entry).use { input ->
                        temp.outputStream().use { output -> IOUtils.copy(input, output) }
                    }
                    if (onEntry(rel, temp)) imported++ else failed++
                } catch (t: Throwable) {
                    failed++
                } finally {
                    temp.delete()
                }
                onProgress(index + 1, jobs.size, rel.substringAfterLast('/'))
            }
            return imported to failed
        } finally {
            runCatching { zip.close() }
        }
    }

    private suspend fun importTar(
        file: File,
        onEntry: suspend (String, File) -> Boolean,
        onProgress: (Int, Int, String) -> Unit,
    ): Pair<Int, Int> {
        // tar 文件名可能是 UTF-8 也可能是 GBK，先探一遍
        var names = collectTarNames(file, "UTF-8")
        var charset = "UTF-8"
        if (names.any { it.contains('\uFFFD') }) {
            names = collectTarNames(file, "GBK")
            charset = "GBK"
        }
        val normalized = names.map { normalizeEntryPath(it) ?: "" }
        val stripped = stripWrapperRoot(normalized)

        val jobs = normalized.mapIndexedNotNull { i, name ->
            val rel = stripped.getOrElse(i) { name }
            if (isUploadJob(rel)) rel else null
        }
        if (jobs.isEmpty()) throw IOException("压缩包里没有按书分目录的音频")

        var imported = 0
        var failed = 0
        var index = -1
        openTar(file, charset).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                index++
                val rel = stripped.getOrElse(index) { "" }
                if (!isUploadJob(rel)) continue

                val temp = File(context.cacheDir, "upload-${System.nanoTime()}")
                try {
                    temp.outputStream().use { output -> IOUtils.copy(tar, output) }
                    if (onEntry(rel, temp)) imported++ else failed++
                } catch (t: Throwable) {
                    failed++
                } finally {
                    temp.delete()
                }
                onProgress(imported + failed, jobs.size, rel.substringAfterLast('/'))
            }
        }
        return imported to failed
    }

    private fun collectTarNames(file: File, charset: String): List<String> {
        val names = ArrayList<String>()
        openTar(file, charset).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isDirectory) names.add(entry.name)
            }
        }
        return names
    }

    private fun openTar(file: File, charset: String): TarArchiveInputStream {
        val raw = file.inputStream().buffered()
        val gzip = GzipCompressorInputStream(raw)
        return TarArchiveInputStream(gzip, charset)
    }

    private fun openZip(file: File): ZipFile {
        val utf8 = runCatching { ZipFile(file, "UTF-8") }.getOrNull()
        if (utf8 != null) {
            val broken = utf8.entries.asSequence().any { it.name.contains('\uFFFD') }
            if (!broken) return utf8
            runCatching { utf8.close() }
        }
        return ZipFile(file, "GBK")
    }

    private fun isUploadJob(rel: String?): Boolean {
        if (rel.isNullOrBlank()) return false
        if (!rel.contains('/')) return false
        if (rel.startsWith("__MACOSX/")) return false
        if (rel.contains(".DS_Store") || rel.contains("Thumbs.db")) return false
        if (!isUploadable(rel)) return false
        return true
    }

    private fun copyToCache(uri: Uri, target: File) {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取文件")
        input.use { source ->
            target.outputStream().use { output -> source.copyTo(output, 64 * 1024) }
        }
    }

    private companion object {
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}
