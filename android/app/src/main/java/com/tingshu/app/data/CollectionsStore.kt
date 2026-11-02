package com.tingshu.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 合集列表缓存；服务端是权威来源，这里保证断网时书架也能显示合集。 */
class CollectionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("tingshu_collections", Context.MODE_PRIVATE)

    fun load(): List<Collection> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Collection>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val ids = ArrayList<String>()
            val bookIds = o.optJSONArray("bookIds")
            if (bookIds != null) {
                for (k in 0 until bookIds.length()) {
                    val v = bookIds.optString(k)
                    if (v.isNotBlank()) ids.add(v)
                }
            }
            out.add(
                Collection(
                    id = id,
                    name = o.optString("name").ifBlank { "未命名合集" },
                    bookIds = ids,
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                )
            )
        }
        return out
    }

    fun save(list: List<Collection>) {
        prefs.edit().putString(KEY, toJson(list).toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY = "collections_json"

        fun toJson(list: List<Collection>): JSONArray {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("bookIds", JSONArray(c.bookIds))
                        .put("createdAt", c.createdAt)
                        .put("updatedAt", c.updatedAt)
                )
            }
            return arr
        }
    }
}
