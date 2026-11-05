package com.tingshu.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App 的本地偏好设置。所有写入都通过 [edit] 收敛到一处，
 * 避免每个 setter 重复写 `prefs.edit().…apply()`。
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tingshu_app", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = edit { putString(KEY_SERVER, value) }

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) = edit { putFloat(KEY_SPEED, value) }

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f)
        set(value) = edit { putFloat(KEY_VOLUME, value) }

    /** 取值见 [ThemeMode.key]。 */
    var theme: String
        get() = prefs.getString(KEY_THEME, ThemeMode.AUTO.key).orEmpty()
        set(value) = edit { putString(KEY_THEME, value) }

    /** 取值见 [SortMode.name]，读取侧用 `SortMode.valueOf`。 */
    var sort: String
        get() = prefs.getString(KEY_SORT, SortMode.RECENT.name).orEmpty()
        set(value) = edit { putString(KEY_SORT, value) }

    var lastBookId: String
        get() = prefs.getString(KEY_LAST_BOOK, "").orEmpty()
        set(value) = edit { putString(KEY_LAST_BOOK, value) }

    var authToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = edit { putString(KEY_TOKEN, value) }

    var authUser: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = edit { putString(KEY_USER, value) }

    var authRole: String
        get() = prefs.getString(KEY_ROLE, "").orEmpty()
        set(value) = edit { putString(KEY_ROLE, value) }

    /** 每行本数，限制在 2~5。 */
    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 2)
        set(value) = edit { putInt(KEY_COLUMNS, value.coerceIn(2, 5)) }

    /** 迷你播放器从下往上滑入的动画时长（毫秒），0 表示直接出现。 */
    var miniAnimMs: Int
        get() = prefs.getInt(KEY_MINI_ANIM, 500)
        set(value) = edit { putInt(KEY_MINI_ANIM, value.coerceIn(0, 3000)) }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_SPEED = "playback_speed"
        const val KEY_VOLUME = "playback_volume"
        const val KEY_THEME = "theme_mode"
        const val KEY_SORT = "sort_mode"
        const val KEY_LAST_BOOK = "last_book_id"
        const val KEY_TOKEN = "auth_token"
        const val KEY_USER = "auth_user"
        const val KEY_ROLE = "auth_role"
        const val KEY_COLUMNS = "layout_columns"
        const val KEY_MINI_ANIM = "mini_anim_ms"
    }
}
