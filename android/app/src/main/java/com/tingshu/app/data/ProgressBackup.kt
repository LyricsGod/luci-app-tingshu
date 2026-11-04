package com.tingshu.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进度备份文件（和网页端 exportProgress 的格式一致）。 */
object ProgressBackup {

    const val VERSION = 1

    /** 只导出真正听过的记录（有进度或有已听完章节）。 */
    fun exportJson(
        progress: Map<String, Progress>,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val items = JSONObject()
        for ((bookId, p) in progress) {
            if (!p.isReal) continue
            items.put(
                bookId,
                JSONObject()
                    .put("index", p.index.coerceAtLeast(0))
                    .put("time", p.time.coerceAtLeast(0.0))
                    .put("done", JSONArray(p.done))
                    .put("updatedAt", p.updatedAt),
            )
        }
        return JSONObject()
            .put("app", "tingshu")
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("items", items)
            .toString(2)
    }

    /** 解析备份；不是听书备份就返回 null。 */
    fun parse(json: String): Map<String, Progress>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val items = root.optJSONObject("items") ?: return null
        val out = LinkedHashMap<String, Progress>()
        val keys = items.keys()
        while (keys.hasNext()) {
            val bookId = keys.next()
            val o = items.optJSONObject(bookId) ?: continue
            val done = ArrayList<Int>()
            val arr = o.optJSONArray("done")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optInt(i, -1)
                    if (n >= 0) done.add(n)
                }
            }
            out[bookId] = Progress(
                index = o.optInt("index", 0).coerceAtLeast(0),
                time = o.optDouble("time", 0.0).coerceAtLeast(0.0),
                done = done,
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
        return out
    }

    fun fileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "tingshu-progress-$stamp.json"
    }
}
