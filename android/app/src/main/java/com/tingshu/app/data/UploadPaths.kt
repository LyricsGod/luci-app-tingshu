package com.tingshu.app.data

/** 上传时允许的文件类型，与服务端 manage 接口的白名单一致。 */
private val UPLOADABLE = Regex(
    "\\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm|ape|wma|" +
        "jpg|jpeg|png|webp|gif|bmp|txt|nfo|m3u|m3u8|json|srt|lrc)$",
    RegexOption.IGNORE_CASE,
)

private val AUDIO_ONLY = Regex(
    "\\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm|ape|wma)$",
    RegexOption.IGNORE_CASE,
)

private val DISC_PATTERNS = listOf(
    Regex("^(cd|disc|disk|dvd|vol|volume|part)[\\s._-]*\\d*$", RegexOption.IGNORE_CASE),
    Regex("^(光盘|碟|碟片|上部|中部|下部|上集|下集|上卷|下卷|上册|下册)\\s*\\d*$"),
    Regex("^第?\\s*[0-9一二三四五六七八九十]{1,3}\\s*[卷部集册章节]?$"),
)

fun isUploadable(path: String): Boolean = UPLOADABLE.containsMatchIn(path)

fun looksLikeDisc(name: String): Boolean = DISC_PATTERNS.any { it.matches(name.trim()) }

/** 规范化压缩包里的路径：反斜杠转正斜杠、去掉开头的 `/`，拒绝 `..`。 */
fun normalizeEntryPath(name: String): String? {
    var p = name.replace('\\', '/')
    while (p.startsWith("/")) p = p.removePrefix("/")
    val parts = p.split('/')
    if (parts.any { it == ".." }) return null
    p = parts.filter { it.isNotEmpty() }.joinToString("/")
    return if (p.isBlank()) null else p
}

/**
 * 和网页端 uploadFiles 里的 stripWrapperRoot 一致：
 * 选中的如果是「装着多本书的大文件夹」，把外层剥掉，让子文件夹各自成书。
 * 音频直接躺在根目录、或子目录像 CD1/光盘1 时，不剥。
 */
fun stripWrapperRoot(names: List<String>): List<String> {
    if (names.isEmpty()) return names

    val audioNames = names.filter { AUDIO_ONLY.containsMatchIn(it) }
    val probe = if (audioNames.isNotEmpty()) audioNames else names

    var minDepth = Int.MAX_VALUE
    var sameRoot = true
    var root: String? = null
    val secondLevel = mutableSetOf<String>()

    for (n in probe) {
        val parts = n.split('/')
        if (parts.size < minDepth) minDepth = parts.size
        if (root == null) {
            root = parts[0]
        } else if (parts[0] != root) {
            sameRoot = false
        }
        if (parts.size >= 3) secondLevel.add(parts[1])
    }

    val r = root ?: return names
    if (!sameRoot) return names
    if (minDepth < 3) return names
    if (secondLevel.size < 2) return names
    if (secondLevel.any { looksLikeDisc(it) }) return names

    val cut = r.length + 1
    return names.map { if (it.length > cut) it.substring(cut) else "" }
}
