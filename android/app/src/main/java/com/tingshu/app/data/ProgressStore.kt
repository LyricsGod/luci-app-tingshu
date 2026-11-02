package com.tingshu.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本机进度缓存；服务端进度是权威来源，这里保证断网/离线时也能续播。 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("tingshu_progress", Context.MODE_PRIVATE)

    fun load(): MutableMap<String, Progress> {
        val raw = prefs.getString(KEY_MAP, null) ?: return mutableMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val out = mutableMapOf<String, Progress>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val bookId = keys.next()
            val o = json.optJSONObject(bookId) ?: continue
            val done = ArrayList<Int>()
            val arr = o.optJSONArray("done")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optInt(i, -1)
                    if (n >= 0) done.add(n)
                }
            }
            out[bookId] = Progress(
                index = o.optInt("index", 0),
                time = o.optDouble("time", 0.0),
                done = done,
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
        return out
    }

    fun save(map: Map<String, Progress>) {
        val json = JSONObject()
        for ((bookId, p) in map) {
            json.put(
                bookId,
                JSONObject()
                    .put("index", p.index)
                    .put("time", p.time)
                    .put("done", JSONArray(p.done))
                    .put("updatedAt", p.updatedAt)
            )
        }
        prefs.edit().putString(KEY_MAP, json.toString()).apply()
    }

    private companion object {
        const val KEY_MAP = "progress_json"
    }
}
