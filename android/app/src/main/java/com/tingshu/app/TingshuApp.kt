package com.tingshu.app

import android.app.Application
import android.content.Context
import com.tingshu.app.data.AppPrefs
import com.tingshu.app.data.CacheStore
import com.tingshu.app.data.CollectionsStore
import com.tingshu.app.data.CoverStore
import com.tingshu.app.data.LanScanner
import com.tingshu.app.data.LibraryRepository
import com.tingshu.app.data.ProgressStore
import com.tingshu.app.data.TingshuApi
import com.tingshu.app.playback.PlaybackController

class TingshuApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 手动依赖注入：依赖很少，没必要上 Hilt。 */
class AppContainer(context: Context) {
    val prefs = AppPrefs(context)
    val progressStore = ProgressStore(context)
    val collectionsStore = CollectionsStore(context)
    val covers = CoverStore(context)
    val cache = CacheStore(context)
    val api = TingshuApi({ prefs.authToken }, cache)
    val repository = LibraryRepository(api, covers)
    val scanner = LanScanner()
    val player = PlaybackController(context)
}
