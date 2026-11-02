package com.tingshu.app.data

import java.io.File

/** 一章音频。url 是拼好的绝对地址，tracks 顺序与目录一致。 */
data class Track(
    val title: String,
    val path: String,
    val url: String,
)

/** 一本书。id 与服务端网页保持一致：`net:<文件夹名>`，进度同步靠它对齐。 */
data class Book(
    val id: String,
    val folderId: String,
    val folderTitle: String,
    val title: String,
    val description: String,
    val palette: Int?,
    val coverFile: File?,
    val coverUrl: String?,
    val tracks: List<Track>,
) {
    val chapterCount: Int get() = tracks.size
    val hasArtwork: Boolean get() = coverFile != null || coverUrl != null
}

/** 听书进度，单位秒；done 是已听完的章节下标。 */
data class Progress(
    val index: Int = 0,
    val time: Double = 0.0,
    val done: List<Int> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isReal: Boolean get() = time > 1.0 || done.isNotEmpty()
}

data class RemoteProgress(
    val index: Int,
    val time: Double,
    val done: List<Int>,
    val updatedAt: Long,
)

/** 服务端 /cgi-ts/meta 里的一条记录。 */
data class MetaRecord(
    val title: String,
    val description: String,
    val palette: Int?,
    val coverBase64: String?,
    val coverType: String?,
    val updatedAt: Long,
)

/** 合集。和服务端 collections 接口的字段一一对应。 */
data class Collection(
    val id: String,
    val name: String,
    val bookIds: List<String>,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 暂停记录。字段与服务端 pausemarks 接口一致。 */
data class PauseMark(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val index: Int,
    val trackTitle: String,
    val time: Double,
    val reason: String,
    val createdAt: Long,
)

data class LibrarySnapshot(
    val books: List<Book>,
    val collections: List<Collection>?,
    val root: String,
    val error: String?,
)

enum class ThemeMode(val key: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    fun next(): ThemeMode = when (this) {
        AUTO -> DARK
        DARK -> LIGHT
        LIGHT -> AUTO
    }

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class SortMode(val label: String) {
    RECENT("最近收听"),
    TITLE("书名"),
    PROGRESS("进度");

    fun next(): SortMode = entries[(ordinal + 1) % entries.size]
}
