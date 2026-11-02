package com.tingshu.app.ui.components

import com.tingshu.app.data.Book
import com.tingshu.app.data.Progress
import kotlin.math.roundToInt

fun bookDoneCount(book: Book, record: Progress?): Int {
    val total = book.chapterCount.coerceAtLeast(1)
    return record?.done?.count { it in 0 until total } ?: 0
}

fun bookFinished(book: Book, record: Progress?): Boolean =
    book.chapterCount > 0 && bookDoneCount(book, record) >= book.chapterCount

/**
 * 和网页端 bookStats() 一致：
 * 有已听章节 + 当前章听了一点（>1 秒）时按 0.35 章估算；没有时长数据也能给出稳定的百分比。
 */
fun bookFraction(book: Book, record: Progress?): Float {
    if (record == null || !record.isReal) return 0f
    val total = book.chapterCount.coerceAtLeast(1)
    val done = bookDoneCount(book, record)
    if (done >= total) return 1f
    val currentDone = record.done.contains(record.index)
    val partial = if (record.time > 1 && !currentDone) 0.35f else 0f
    return ((done + partial) / total).coerceIn(0f, 1f)
}

fun bookPercent(book: Book, record: Progress?): String =
    "${(bookFraction(book, record) * 100).roundToInt()}%"
