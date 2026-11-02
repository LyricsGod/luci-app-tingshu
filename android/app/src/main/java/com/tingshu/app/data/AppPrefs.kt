package com.tingshu.app.data

import android.content.Context

class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("tingshu_app", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER, value).apply()
        }

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_SPEED, value).apply()
        }

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_VOLUME, value).apply()
        }

    var theme: String
        get() = prefs.getString(KEY_THEME, ThemeMode.AUTO.key).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
        }

    var sort: String
        get() = prefs.getString(KEY_SORT, SortMode.RECENT.name).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SORT, value).apply()
        }

    var lastBookId: String
        get() = prefs.getString(KEY_LAST_BOOK, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_BOOK, value).apply()
        }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_SPEED = "playback_speed"
        const val KEY_VOLUME = "playback_volume"
        const val KEY_THEME = "theme_mode"
        const val KEY_SORT = "sort_mode"
        const val KEY_LAST_BOOK = "last_book_id"
    }
}
