package com.tingshu.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 暂停记录缓存。服务端 pausemarks 接口整包读写。 */
class PauseMarksStore(context: Context) {

    private val prefs = context.getSharedPreferences("tingshu_pausemarks", Context.MODE_PRIVATE)

    fun load(): List<PauseMark> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PauseMark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val bookId = o.optString("bookId")
            if (id.isBlank() || bookId.isBlank()) continue
            out.add(
                PauseMark(
                    id = id,
                    bookId = bookId,
                    bookTitle = o.optString("bookTitle"),
                    index = o.optInt("index"),
                    trackTitle = o.optString("trackTitle"),
                    time = o.optDouble("time", 0.0),
                    reason = o.optString("reason").ifBlank { "pause" },
                    createdAt = o.optLong("createdAt"),
                    source = o.optString("source"),
                )
            )
        }
        return out
    }

    fun save(list: List<PauseMark>) {
        prefs.edit().putString(KEY, toJson(list).toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY = "pausemarks_json"

        fun toJson(list: List<PauseMark>): JSONArray {
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("bookId", m.bookId)
                        .put("bookTitle", m.bookTitle)
                        .put("index", m.index)
                        .put("trackTitle", m.trackTitle)
                        .put("time", m.time)
                        .put("reason", m.reason)
                        .put("createdAt", m.createdAt)
                        .put("source", m.source)
                )
            }
            return arr
        }
    }
}
