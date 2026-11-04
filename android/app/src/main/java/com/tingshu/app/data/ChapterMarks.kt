package com.tingshu.app.data

/** 章节「已读完」标记的纯函数工具：全本 / 多选批量标已读、未读都走这里。 */
object ChapterMarks {

    /**
     * 把 [indices] 里的章节标成已读或未读，返回排序去重后的新集合。
     * 越界下标会被忽略，[current] 里越界的旧记录也顺手丢掉（换文件后章节数变少时有用）。
     */
    fun apply(current: List<Int>, indices: Iterable<Int>, done: Boolean, total: Int): List<Int> {
        val valid = indices.filter { it in 0 until total }.toSet()
        val keep = current.filter { it in 0 until total }.toSet()
        val next = if (done) keep + valid else keep - valid
        return next.sorted()
    }
}
