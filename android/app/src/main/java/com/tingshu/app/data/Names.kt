package com.tingshu.app.data

/** 自然排序：`02 章` 排在 `10 章` 前面，和网页端 Intl.Collator(numeric) 的直觉一致。 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ei
            j = ej
        } else {
            if (ca != cb) return ca.lowercaseChar().compareTo(cb.lowercaseChar())
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** 去掉「01.」「003 -」「4）」这类章节序号，纯数字标题（如 1984）不动。 */
fun cleanTrackTitle(name: String): String {
    // 服务端 library.json 里的 title 已经是去掉扩展名的文件名，这里不再剥后缀，
    // 否则「01. 地球往事」会被当成扩展名整个吃掉。
    var s = name.replace(Regex("^\\s*\\d{1,3}\\s*[.\\-_—、)）]\\s*"), "")
    s = s.replace(Regex("^\\s*\\d{1,3}\\s+(?=\\S)"), "")
    return s.trim().ifEmpty { name }
}
