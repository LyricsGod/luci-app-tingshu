package com.tingshu.app.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.tingshu.app.TingshuApp
import com.tingshu.app.data.ArchiveImporter
import com.tingshu.app.data.Book
import com.tingshu.app.data.Collection
import com.tingshu.app.data.Hex
import com.tingshu.app.data.PauseMark
import com.tingshu.app.data.PauseMarksStore
import com.tingshu.app.data.Progress
import com.tingshu.app.data.ProgressBackup
import com.tingshu.app.data.SortMode
import com.tingshu.app.data.ThemeMode
import com.tingshu.app.data.TingshuApi
import com.tingshu.app.data.UploadState
import com.tingshu.app.data.Urls
import com.tingshu.app.data.isUploadable
import com.tingshu.app.data.stripWrapperRoot
import com.tingshu.app.playback.PlaybackController
import com.tingshu.app.playback.PlayerUiState
import com.tingshu.app.playback.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Ready : LoadState
    data class Error(val message: String) : LoadState
}

data class SleepState(
    val active: Boolean = false,
    val endsAt: Long = 0L,
    val minutes: Int = 0,
    val atChapterEnd: Boolean = false,
    val finishCurrentChapter: Boolean = false,
)

/**
 * 整个 App 的状态中枢：书库、进度、合集、暂停记录、播放器、上传、备份。
 */
class TingshuViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as TingshuApp).container
    private val api = container.api
    private val prefs = container.prefs
    private val progressStore = container.progressStore
    private val collectionsStore = container.collectionsStore
    private val pauseMarkStore = PauseMarksStore(application)
    private val repository = container.repository
    private val player = container.player
    private val covers = container.covers
    private val importer = ArchiveImporter(application)

    val serverUrl = MutableStateFlow(prefs.serverUrl)
    val books = MutableStateFlow<List<Book>>(emptyList())
    val collections = MutableStateFlow(collectionsStore.load())
    val libraryRoot = MutableStateFlow("")
    val loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val progress = MutableStateFlow<Map<String, Progress>>(progressStore.load())
    val pauseMarks = MutableStateFlow(pauseMarkStore.load())

    val search = MutableStateFlow("")
    val sort = MutableStateFlow(runCatching { SortMode.valueOf(prefs.sort) }.getOrDefault(SortMode.RECENT))
    val theme = MutableStateFlow(ThemeMode.from(prefs.theme))
    val volume = MutableStateFlow(prefs.volume)

    val connecting = MutableStateFlow(false)
    val connectError = MutableStateFlow<String?>(null)
    val scanning = MutableStateFlow(false)
    val foundServers = MutableStateFlow<List<String>>(emptyList())
    val refreshing = MutableStateFlow(false)
    val rescanning = MutableStateFlow(false)
    val sleep = MutableStateFlow(SleepState())
    val uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val backupInfo = MutableStateFlow<TingshuApi.BackupInfo?>(null)
    val backupBusy = MutableStateFlow(false)

    val needsLogin = MutableStateFlow(prefs.authToken.isBlank())
    val authUser = MutableStateFlow(prefs.authUser)
    val authRole = MutableStateFlow(prefs.authRole)
    val authBusy = MutableStateFlow(false)
    val authError = MutableStateFlow<String?>(null)
    val authConfigured = MutableStateFlow(true)
    val columns = MutableStateFlow(prefs.columns)
    val users = MutableStateFlow<List<TingshuApi.UserInfo>>(emptyList())
    val usersLoading = MutableStateFlow(false)
    val usersError = MutableStateFlow<String?>(null)

    val isAdmin: Boolean get() = authRole.value == "admin"

    val playerState: StateFlow<PlayerUiState> = player.state
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var syncEnabled = false
        private set

    private var reloading = false
    private var lastProgressAt = 0L
    private var wasPlaying = false
    private var pauseMarkGuardUntil = 0L
    private var persistJob: Job? = null
    private var sleepJob: Job? = null
    private var collectionsPushJob: Job? = null
    private var pausePushJob: Job? = null
    private val lastPushAt = HashMap<String, Long>()
    private val lastPushKey = HashMap<String, String>()

    init {
        viewModelScope.launch { player.state.collect { onPlayerUpdate(it) } }
        viewModelScope.launch { player.transitions.collect { onTransition(it) } }
        player.setVolume(volume.value)
        if (prefs.authToken.isNotBlank() && prefs.serverUrl.isNotBlank()) {
            viewModelScope.launch {
                val base = prefs.serverUrl
                val ok = runCatching { api.authStatus(base).authenticated }.getOrDefault(false)
                if (!ok) {
                    needsLogin.value = true
                } else {
                    reload(initial = true)
                }
            }
        }
    }

    /* ---------------- 书库 ---------------- */

    fun reload(initial: Boolean = false) {
        val base = serverUrl.value.ifBlank { prefs.serverUrl }
        if (base.isBlank()) {
            loadState.value = LoadState.Idle
            return
        }
        if (reloading) return

        viewModelScope.launch {
            reloading = true
            refreshing.value = true
            if (initial || books.value.isEmpty()) loadState.value = LoadState.Loading
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.load(base) }
                books.value = snapshot.books
                libraryRoot.value = snapshot.root
                serverUrl.value = base
                prefs.serverUrl = base
                snapshot.collections?.let { mergeCollections(it) }
                loadState.value =
                    if (snapshot.books.isEmpty() && snapshot.error != null) LoadState.Error(snapshot.error)
                    else LoadState.Ready
                pullProgress(base)
                pullPauseMarks(base)
                syncEnabled = true
            } catch (t: Throwable) {
                if (isAuthError(t)) {
                    needsLogin.value = true
                } else {
                    val message = humanError(t)
                    if (books.value.isEmpty()) {
                        loadState.value = LoadState.Error(message)
                    } else {
                        messages.tryEmit(message)
                    }
                }
            } finally {
                refreshing.value = false
                reloading = false
            }
        }
    }

    fun connect(input: String) {
        val base = Urls.normalizeServer(input)
        if (base == null) {
            connectError.value = "地址格式不对，例如 192.168.1.1:8686"
            return
        }
        viewModelScope.launch {
            connecting.value = true
            connectError.value = null
            val ok = withContext(Dispatchers.IO) { runCatching { api.probe(base) }.getOrDefault(false) }
            connecting.value = false
            if (!ok) {
                connectError.value = "连不上 $base，确认服务已在路由器上启动"
                return@launch
            }
            prefs.serverUrl = base
            serverUrl.value = base
            // 换服务器后必须用新服务器上的账号重新登录
            prefs.authToken = ""
            prefs.authUser = ""
            prefs.authRole = ""
            authUser.value = ""
            authRole.value = ""
            needsLogin.value = true
        }
    }

    /**
     * 服务器第一次使用时会自动创建管理员；否则走普通登录。
     * 成功后保存 token，后续所有 CGI 请求都会带上 X-Auth-Token。
     */
    fun authenticate(address: String, user: String, password: String) {
        val input = address.trim().ifBlank { prefs.serverUrl }
        val base = Urls.normalizeServer(input)
        if (base == null) {
            authError.value = "地址格式不对，例如 192.168.1.1:8686"
            return
        }
        if (user.isBlank()) {
            authError.value = "请输入用户名"
            return
        }
        if (password.length < 6) {
            authError.value = "密码至少 6 位"
            return
        }
        if (authBusy.value) return

        viewModelScope.launch {
            authBusy.value = true
            authError.value = null
            try {
                val status = withContext(Dispatchers.IO) { api.authStatus(base) }
                authConfigured.value = status.configured
                if (!status.configured && user.length < 1) {
                    authError.value = "请设置管理员用户名"
                    return@launch
                }
                val action = if (status.configured) "login" else "setup"
                val session = withContext(Dispatchers.IO) {
                    api.auth(base, action, user, password)
                }
                val previous = prefs.authUser
                if (previous.isBlank() || previous != session.user) clearUserData()

                prefs.serverUrl = base
                prefs.authToken = session.token
                prefs.authUser = session.user
                prefs.authRole = session.role
                serverUrl.value = base
                authUser.value = session.user
                authRole.value = session.role
                needsLogin.value = false
                connectError.value = null

                loadLayout(base)
                reload(initial = true)
                messages.tryEmit(if (action == "setup") "管理员已创建" else "已登录：${session.user}")
            } catch (t: TingshuApi.ApiException) {
                authError.value = when {
                    t.code == 401 -> "用户名或密码不对"
                    t.code == 403 -> "服务器已经初始化过了"
                    else -> "登录失败（HTTP ${t.code}）"
                }
            } catch (t: Throwable) {
                authError.value = "连不上服务器：${t.message ?: "请检查网络"}"
            } finally {
                authBusy.value = false
            }
        }
    }

    fun logout() {
        val base = serverUrl.value
        viewModelScope.launch {
            if (base.isNotBlank()) {
                withContext(Dispatchers.IO) { runCatching { api.logout(base) } }
            }
            prefs.authToken = ""
            prefs.authUser = ""
            prefs.authRole = ""
            authUser.value = ""
            authRole.value = ""
            needsLogin.value = true
            books.value = emptyList()
            collections.value = emptyList()
            progress.value = emptyMap()
            pauseMarks.value = emptyList()
            loadState.value = LoadState.Idle
            player.stop()
        }
    }

    fun setColumns(value: Int) {
        val next = value.coerceIn(2, 5)
        columns.value = next
        prefs.columns = next
        val base = serverUrl.value
        if (base.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { api.pushLayoutColumns(base, next) } }
        }
    }

    fun loadUsers() {
        if (!isAdmin) return
        val base = serverUrl.value
        if (base.isBlank()) return
        viewModelScope.launch {
            usersLoading.value = true
            usersError.value = null
            try {
                users.value = withContext(Dispatchers.IO) { api.listUsers(base) }
            } catch (t: Throwable) {
                usersError.value = if (isAuthError(t)) "登录已过期" else (t.message ?: "加载用户失败")
                if (isAuthError(t)) needsLogin.value = true
            } finally {
                usersLoading.value = false
            }
        }
    }

    fun createUser(user: String, password: String, role: String) {
        if (!isAdmin) return
        val base = serverUrl.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.createUser(base, user, password, role) }
                messages.tryEmit("用户已创建")
                usersError.value = null
                loadUsers()
            } catch (t: Throwable) {
                usersError.value = if (isAuthError(t)) "登录已过期" else (t.message ?: "创建失败")
                if (isAuthError(t)) needsLogin.value = true
            }
        }
    }

    fun resetUserPassword(user: String, password: String) {
        if (!isAdmin) return
        val base = serverUrl.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.resetPassword(base, user, password) }
                messages.tryEmit("密码已重置")
                usersError.value = null
            } catch (t: Throwable) {
                usersError.value = if (isAuthError(t)) "登录已过期" else (t.message ?: "重置失败")
                if (isAuthError(t)) needsLogin.value = true
            }
        }
    }

    fun deleteUser(user: String) {
        if (!isAdmin) return
        val base = serverUrl.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.deleteUser(base, user) }
                messages.tryEmit("用户已删除")
                usersError.value = null
                loadUsers()
            } catch (t: Throwable) {
                usersError.value = if (isAuthError(t)) "登录已过期" else (t.message ?: "删除失败")
                if (isAuthError(t)) needsLogin.value = true
            }
        }
    }

    private suspend fun loadLayout(base: String) {
        val remote = runCatching { api.fetchLayoutColumns(base) }.getOrNull()
        if (remote != null && remote > 0) {
            columns.value = remote
            prefs.columns = remote
        }
    }

    private fun clearUserData() {
        progressStore.clear()
        collectionsStore.clear()
        pauseMarkStore.clear()
        progress.value = emptyMap()
        collections.value = emptyList()
        pauseMarks.value = emptyList()
    }

    private fun isAuthError(t: Throwable): Boolean =
        t is TingshuApi.ApiException && t.code == 401

    fun scanLan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            foundServers.value = emptyList()
            connectError.value = null
            val found = withContext(Dispatchers.IO) {
                runCatching { container.scanner.scan() }.getOrDefault(emptyList())
            }
            foundServers.value = found
            scanning.value = false
            if (found.isEmpty()) {
                connectError.value = "没扫到听书服务（端口 8686），可以手动填地址"
            }
        }
    }

    fun rescanLibrary() {
        val base = serverUrl.value
        if (base.isBlank() || rescanning.value) return
        viewModelScope.launch {
            rescanning.value = true
            try {
                val (bookCount, trackCount) = withContext(Dispatchers.IO) { api.rescan(base) }
                messages.tryEmit("重新扫描完成：$bookCount 本书 / $trackCount 个章节")
                reload()
            } catch (t: Throwable) {
                messages.tryEmit("扫描失败：${humanError(t)}")
            } finally {
                rescanning.value = false
            }
        }
    }

    fun disconnect() {
        prefs.serverUrl = ""
        serverUrl.value = ""
        books.value = emptyList()
        libraryRoot.value = ""
        loadState.value = LoadState.Idle
        syncEnabled = false
    }

    /* ---------------- 搜索 / 排序 / 主题 / 音量 ---------------- */

    fun setSearch(value: String) {
        search.value = value
    }

    fun cycleSort() {
        val next = sort.value.next()
        sort.value = next
        prefs.sort = next.name
        messages.tryEmit("排序：${next.label}")
    }

    fun cycleTheme() {
        setTheme(theme.value.next())
    }

    fun setTheme(mode: ThemeMode) {
        theme.value = mode
        prefs.theme = mode.key
        messages.tryEmit(
            when (mode) {
                ThemeMode.AUTO -> "跟随系统"
                ThemeMode.DARK -> "夜间模式"
                ThemeMode.LIGHT -> "日间模式"
            }
        )
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        volume.value = v
        prefs.volume = v
        player.setVolume(v)
    }

    /* ---------------- 合集 ---------------- */

    fun collectionFor(bookId: String): Collection? =
        collections.value.firstOrNull { bookId in it.bookIds }

    private fun mergeCollections(remote: List<Collection>) {
        if (remote.isEmpty()) {
            if (collections.value.isEmpty()) return
            // 服务端说没有合集：以服务端为准，避免旧缓存把已删合集又推回去
            collections.value = emptyList()
            collectionsStore.save(emptyList())
            return
        }
        val local = collections.value.associateBy { it.id }
        val merged = LinkedHashMap<String, Collection>()
        var localNewer = false
        for (r in remote) {
            val l = local[r.id]
            if (l != null && l.updatedAt > r.updatedAt) {
                merged[r.id] = l
                localNewer = true
            } else {
                merged[r.id] = r
            }
        }
        collections.value = merged.values.toList()
        collectionsStore.save(collections.value)
        if (localNewer) schedulePushCollections()
    }

    private fun saveCollections(list: List<Collection>, push: Boolean = true) {
        collections.value = list
        collectionsStore.save(list)
        if (push) schedulePushCollections()
    }

    private fun schedulePushCollections() {
        collectionsPushJob?.cancel()
        collectionsPushJob = viewModelScope.launch {
            delay(400)
            val base = serverUrl.value
            if (base.isBlank() || !syncEnabled) return@launch
            val snapshot = collections.value
            withContext(Dispatchers.IO) { runCatching { api.pushCollections(base, snapshot) } }
        }
    }

    private fun withoutBooks(
        list: List<Collection>,
        ids: Set<String>,
        exceptId: String?,
        now: Long,
    ): List<Collection> = list.map { c ->
        if (c.id == exceptId) {
            c
        } else {
            val kept = c.bookIds.filter { it !in ids }
            if (kept.size != c.bookIds.size) c.copy(bookIds = kept, updatedAt = now) else c
        }
    }

    fun createCollection(name: String, bookIds: List<String>) {
        if (bookIds.isEmpty()) {
            messages.tryEmit("至少挑一本书")
            return
        }
        val now = System.currentTimeMillis()
        val id = "c" + now.toString(36) + (100..999).random().toString(36)
        val cleaned = bookIds.distinct()
        val others = withoutBooks(collections.value, cleaned.toSet(), exceptId = null, now)
        val col = Collection(
            id = id,
            name = name.trim().ifBlank { "未命名合集" },
            bookIds = cleaned,
            createdAt = now,
            updatedAt = now,
        )
        saveCollections(others + col)
        messages.tryEmit("合集「${col.name}」已创建")
    }

    fun updateCollection(id: String, name: String, bookIds: List<String>) {
        if (bookIds.isEmpty()) {
            messages.tryEmit("至少挑一本书")
            return
        }
        val existing = collections.value.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val wanted = bookIds.distinct()
        val prev = existing.bookIds.filter { it in wanted }
        val added = wanted.filter { it !in prev }
        val ordered = prev + added
        val others = withoutBooks(collections.value, ordered.toSet(), exceptId = id, now)
        saveCollections(
            others.map {
                if (it.id == id) {
                    it.copy(name = name.trim().ifBlank { "未命名合集" }, bookIds = ordered, updatedAt = now)
                } else {
                    it
                }
            }
        )
        messages.tryEmit("合集已更新")
    }

    fun deleteCollection(id: String) {
        val col = collections.value.firstOrNull { it.id == id } ?: return
        saveCollections(collections.value.filterNot { it.id == id })
        messages.tryEmit("合集「${col.name}」已删除")
    }

    fun toggleBookInCollection(book: Book, collectionId: String) {
        val col = collections.value.firstOrNull { it.id == collectionId } ?: return
        val now = System.currentTimeMillis()
        if (book.id in col.bookIds) {
            saveCollections(
                collections.value.map {
                    if (it.id == collectionId) it.copy(bookIds = it.bookIds - book.id, updatedAt = now) else it
                }
            )
            messages.tryEmit("已移出「${col.name}」")
            return
        }
        val others = withoutBooks(collections.value, setOf(book.id), exceptId = collectionId, now)
        saveCollections(
            others.map {
                if (it.id == collectionId) it.copy(bookIds = it.bookIds + book.id, updatedAt = now) else it
            }
        )
        messages.tryEmit("已加入「${col.name}」")
    }

    fun removeBookFromCollection(bookId: String, collectionId: String) {
        val now = System.currentTimeMillis()
        saveCollections(
            collections.value.map {
                if (it.id == collectionId) it.copy(bookIds = it.bookIds - bookId, updatedAt = now) else it
            }
        )
    }

    fun moveCollectionBook(collectionId: String, from: Int, to: Int) {
        val col = collections.value.firstOrNull { it.id == collectionId } ?: return
        if (from !in col.bookIds.indices || to !in col.bookIds.indices || from == to) return
        val ids = col.bookIds.toMutableList()
        val item = ids.removeAt(from)
        ids.add(to, item)
        val now = System.currentTimeMillis()
        saveCollections(
            collections.value.map {
                if (it.id == collectionId) it.copy(bookIds = ids, updatedAt = now) else it
            }
        )
    }

    /* ---------------- 暂停记录 ---------------- */

    fun recordPauseMark(reason: String) {
        addPauseMark(reason, playerState.value)
    }

    private fun addPauseMark(reason: String, state: PlayerUiState) {
        val bookId = state.bookId ?: return
        val now = System.currentTimeMillis()
        val time = state.positionMs / 1000.0
        if (time < 1 && reason != "timer-start") return

        val book = books.value.firstOrNull { it.id == bookId }
        val index = state.index.coerceAtLeast(0)
        val trackTitle = book?.tracks?.getOrNull(index)?.title ?: state.trackTitle
        val mark = PauseMark(
            id = "pm-$now-${(1000..9999).random()}",
            bookId = bookId,
            bookTitle = book?.title ?: state.bookTitle,
            index = index,
            trackTitle = trackTitle,
            time = time,
            reason = reason,
            createdAt = now,
            source = PauseMark.SOURCE_APP,
        )
        pauseMarks.value = (pauseMarks.value + mark).sortedByDescending { it.createdAt }
        pauseMarkStore.save(pauseMarks.value)
        pauseMarkGuardUntil = now + 2000
        schedulePushPauseMarks()
    }

    fun deletePauseMark(id: String) {
        pauseMarks.value = pauseMarks.value.filterNot { it.id == id }
        pauseMarkStore.save(pauseMarks.value)
        schedulePushPauseMarks()
        messages.tryEmit("已删除这条记录")
    }

    fun clearPauseMarks(bookId: String? = null) {
        pauseMarks.value = if (bookId == null) {
            emptyList()
        } else {
            pauseMarks.value.filterNot { it.bookId == bookId }
        }
        pauseMarkStore.save(pauseMarks.value)
        schedulePushPauseMarks()
        messages.tryEmit("已清空暂停记录")
    }

    fun pauseMarksFor(bookId: String?): List<PauseMark> =
        pauseMarks.value.filter { bookId == null || it.bookId == bookId }

    fun playFromMark(mark: PauseMark) {
        val book = books.value.firstOrNull { it.id == mark.bookId }
        if (book == null) {
            messages.tryEmit("这本书已不在书架")
            return
        }
        playBook(book, startIndex = mark.index, positionMs = (mark.time * 1000).toLong())
    }

    private fun schedulePushPauseMarks() {
        pausePushJob?.cancel()
        pausePushJob = viewModelScope.launch {
            delay(500)
            val base = serverUrl.value
            if (base.isBlank()) return@launch
            val snapshot = pauseMarks.value
            withContext(Dispatchers.IO) { runCatching { api.pushPauseMarks(base, snapshot) } }
        }
    }

    private suspend fun pullPauseMarks(base: String) {
        val remote = withContext(Dispatchers.IO) {
            runCatching { api.fetchPauseMarks(base) }.getOrNull()
        } ?: return

        val local = pauseMarks.value
        val map = local.associateBy { it.id }.toMutableMap()
        var changed = false
        for (r in remote) {
            val l = map[r.id]
            if (l == null || l.createdAt <= r.createdAt) {
                if (l != r) changed = true
                map[r.id] = r
            }
        }
        val localOnly = local.any { l -> remote.none { it.id == l.id } }
        if (changed || localOnly) {
            pauseMarks.value = map.values.sortedByDescending { it.createdAt }
            pauseMarkStore.save(pauseMarks.value)
            if (localOnly) schedulePushPauseMarks()
        }
    }

    /* ---------------- 进度同步 ---------------- */

    private suspend fun pullProgress(base: String) {
        val remote = withContext(Dispatchers.IO) {
            runCatching { api.fetchProgress(base) }.getOrNull()
        } ?: return
        if (remote.isEmpty()) return

        val merged = progress.value.toMutableMap()
        var changed = false
        for ((hex, rec) in remote) {
            val bookId = Hex.decode(hex) ?: continue
            val local = merged[bookId]
            val localReal = local?.isReal == true
            val remoteReal = rec.time > 1.0 || rec.done.isNotEmpty()
            if (localReal && (local?.updatedAt ?: 0L) >= rec.updatedAt) continue
            if (!localReal && !remoteReal) continue
            merged[bookId] = Progress(rec.index, rec.time, rec.done, rec.updatedAt)
            changed = true
        }
        if (changed) {
            progress.value = merged
            progressStore.save(merged)
        }
    }

    private fun onPlayerUpdate(state: PlayerUiState) {
        val now = System.currentTimeMillis()
        val paused = wasPlaying && !state.isPlaying
        if (paused && now >= pauseMarkGuardUntil) {
            addPauseMark("pause", state)
        }
        wasPlaying = state.isPlaying

        val bookId = state.bookId ?: return
        if (now - lastProgressAt < 900 && !paused) return
        lastProgressAt = now

        val current = progress.value[bookId] ?: Progress()
        val seconds = state.positionMs / 1000.0
        val time = if (state.index != current.index && state.positionMs < 1500) 0.0 else seconds
        val updated = current.copy(
            index = state.index,
            time = time,
            updatedAt = now,
        )
        progress.value = progress.value + (bookId to updated)
        persistSoon()
        pushProgress(bookId, updated, force = paused)
    }

    private fun onTransition(transition: Transition) {
        val finished = transition.reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            transition.reason == Transition.REASON_ENDED
        if (!finished) return

        val current = progress.value[transition.bookId] ?: Progress()
        val done = (current.done + transition.fromIndex).distinct().sorted()
        val updated = current.copy(
            index = transition.toIndex,
            time = 0.0,
            done = done,
            updatedAt = System.currentTimeMillis(),
        )
        progress.value = progress.value + (transition.bookId to updated)
        progressStore.save(progress.value)
        pushProgress(transition.bookId, updated, force = true)

        if (sleep.value.atChapterEnd) {
            addPauseMark("timer", playerState.value)
            player.pause()
            sleep.value = SleepState()
            messages.tryEmit("本章已播完，定时暂停")
        }
    }

    private fun persistSoon() {
        if (persistJob?.isActive == true) return
        persistJob = viewModelScope.launch {
            delay(1500)
            progressStore.save(progress.value)
        }
    }

    fun flush() {
        persistJob?.cancel()
        progressStore.save(progress.value)
        pauseMarkStore.save(pauseMarks.value)
        val state = playerState.value
        val bookId = state.bookId ?: return
        val record = progress.value[bookId] ?: return
        pushProgress(bookId, record, force = true)
    }

    private fun pushProgress(bookId: String, record: Progress, force: Boolean) {
        if (!syncEnabled) return
        val base = serverUrl.value
        if (base.isBlank()) return

        val key = "${record.index}|${record.time}|${record.done.joinToString(",")}"
        if (lastPushKey[bookId] == key) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastPushAt[bookId] ?: 0L) < 15000) return
        lastPushAt[bookId] = now
        lastPushKey[bookId] = key

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { api.pushProgress(base, bookId, record) }
            }
        }
    }

    /* ---------------- 编辑 / 删除书籍 ---------------- */

    fun saveBookMeta(
        book: Book,
        title: String,
        description: String,
        palette: Int?,
        coverBytes: ByteArray? = null,
        coverType: String? = null,
        removeCover: Boolean = false,
    ) {
        viewModelScope.launch {
            val hex = Hex.encode(book.id)
            val newCover = withContext(Dispatchers.IO) {
                when {
                    coverBytes != null -> covers.saveRaw(hex, coverBytes)
                    removeCover -> {
                        covers.delete(hex)
                        null
                    }
                    else -> book.coverFile
                }
            }
            val pushBytes = withContext(Dispatchers.IO) {
                when {
                    coverBytes != null -> coverBytes
                    removeCover -> null
                    else -> newCover?.let { file ->
                        runCatching {
                            if (file.length() in 1..MAX_COVER_PUSH_BYTES) file.readBytes() else null
                        }.getOrNull()
                    }
                }
            }

            val finalTitle = title.trim().ifBlank { book.folderTitle }
            val storeTitle = if (finalTitle == book.folderTitle) "" else finalTitle
            val newDesc = description.trim()
            val base = serverUrl.value
            val ok = if (base.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        api.pushMeta(
                            base = base,
                            bookId = book.id,
                            title = storeTitle,
                            description = newDesc,
                            palette = palette,
                            coverBytes = pushBytes,
                            coverType = if (pushBytes != null) coverType ?: "image/jpeg" else null,
                        )
                    }.getOrDefault(false)
                }
            } else {
                false
            }

            books.value = books.value.map { b ->
                if (b.id == book.id) {
                    b.copy(
                        title = finalTitle,
                        description = newDesc,
                        palette = palette,
                        coverFile = newCover,
                    )
                } else {
                    b
                }
            }
            messages.tryEmit(if (ok) "已保存并同步" else "已保存到本机，但没同步到路由器")
        }
    }

    fun deleteBook(book: Book) {
        val base = serverUrl.value
        if (base.isBlank()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.deleteBook(base, book.folderId) }.getOrDefault(false)
            }
            if (!ok) {
                messages.tryEmit("删除失败，检查路由器和书架目录")
                return@launch
            }
            if (playerState.value.bookId == book.id) player.stop()

            val hex = Hex.encode(book.id)
            withContext(Dispatchers.IO) { covers.delete(hex) }

            progress.value = progress.value - book.id
            progressStore.save(progress.value)

            val now = System.currentTimeMillis()
            if (collections.value.any { book.id in it.bookIds }) {
                saveCollections(
                    collections.value.map {
                        if (book.id in it.bookIds) {
                            it.copy(bookIds = it.bookIds - book.id, updatedAt = now)
                        } else {
                            it
                        }
                    }
                )
            }

            val marks = pauseMarks.value.filterNot { it.bookId == book.id }
            if (marks.size != pauseMarks.value.size) {
                pauseMarks.value = marks
                pauseMarkStore.save(marks)
                schedulePushPauseMarks()
            }

            withContext(Dispatchers.IO) { runCatching { api.rescan(base) } }
            reload()
            messages.tryEmit("已删除「${book.title}」")
        }
    }

    /** 读取图片（按需降采样），交给裁切弹窗让用户自己选区域。 */
    suspend fun loadCoverBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /* ---------------- 上传 ---------------- */

    fun uploadFolder(treeUri: Uri) {
        val base = serverUrl.value
        if (base.isBlank()) {
            uploadState.value = UploadState.Error("还没连接服务器")
            return
        }
        if (uploadState.value is UploadState.Running) return

        viewModelScope.launch {
            uploadState.value = UploadState.Running(label = "正在读取文件夹…")
            try {
                val files = withContext(Dispatchers.IO) { collectFolderFiles(treeUri) }
                if (files.isEmpty()) {
                    uploadState.value = UploadState.Error("没有可上传的音频文件")
                    return@launch
                }
                var bytes = 0L
                var ok = 0
                var failed = 0
                files.forEachIndexed { index, pair ->
                    val (rel, uri) = pair
                    val size = withContext(Dispatchers.IO) {
                        val temp = runCatching { copyUriToTemp(uri) }.getOrNull()
                        if (temp == null) {
                            null
                        } else {
                            try {
                                api.uploadFile(base, rel, temp)
                            } finally {
                                temp.delete()
                            }
                        }
                    }
                    if (size != null) {
                        ok++
                        bytes += size
                    } else {
                        failed++
                    }
                    uploadState.value = UploadState.Running(
                        done = index + 1,
                        total = files.size,
                        bytes = bytes,
                        label = rel.substringAfterLast('/'),
                    )
                }
                uploadState.value = UploadState.Running(files.size, files.size, bytes, "正在整理书库…")
                withContext(Dispatchers.IO) { runCatching { api.rescan(base) } }
                reload()
                uploadState.value = UploadState.Done(ok, failed)
            } catch (t: Throwable) {
                uploadState.value = UploadState.Error(t.message ?: "上传失败")
            }
        }
    }

    fun uploadArchive(uri: Uri, displayName: String) {
        val base = serverUrl.value
        if (base.isBlank()) {
            uploadState.value = UploadState.Error("还没连接服务器")
            return
        }
        if (uploadState.value is UploadState.Running) return

        viewModelScope.launch {
            uploadState.value = UploadState.Running(label = "正在解析压缩包…")
            try {
                val (ok, failed) = withContext(Dispatchers.IO) {
                    importer.import(
                        uri = uri,
                        displayName = displayName,
                        onEntry = { rel, file -> api.uploadFile(base, rel, file) != null },
                        onProgress = { done, total, name ->
                            uploadState.value = UploadState.Running(done, total, 0L, name)
                        },
                    )
                }
                uploadState.value = UploadState.Running(ok + failed, ok + failed, 0L, "正在整理书库…")
                withContext(Dispatchers.IO) { runCatching { api.rescan(base) } }
                reload()
                uploadState.value = UploadState.Done(ok, failed)
            } catch (t: Throwable) {
                uploadState.value = UploadState.Error(t.message ?: "导入失败")
            }
        }
    }

    fun resetUploadState() {
        uploadState.value = UploadState.Idle
    }

    private fun collectFolderFiles(treeUri: Uri): List<Pair<String, Uri>> {
        val resolver = getApplication<Application>().contentResolver
        val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return emptyList()
        val rootName = root.name?.takeIf { it.isNotBlank() } ?: "上传"
        val raw = ArrayList<Pair<String, Uri>>()

        fun walk(dir: DocumentFile, prefix: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name.startsWith(".")) return@forEach
                if (child.isDirectory) {
                    walk(child, "$prefix$name/")
                } else if (isUploadable(name)) {
                    raw.add(("$prefix$name") to child.uri)
                }
            }
        }
        walk(root, "$rootName/")
        if (raw.isEmpty()) return emptyList()

        val stripped = stripWrapperRoot(raw.map { it.first })
        return raw.mapIndexedNotNull { i, pair ->
            val rel = stripped.getOrElse(i) { pair.first }
            if (rel.contains('/')) rel to pair.second else null
        }
    }

    private fun copyUriToTemp(uri: Uri): File {
        val app = getApplication<Application>()
        val temp = File(app.cacheDir, "upload-${System.nanoTime()}.bin")
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件")
        input.use { source ->
            temp.outputStream().use { output -> source.copyTo(output, 64 * 1024) }
        }
        return temp
    }

    /* ---------------- 服务器备份（仅管理员） ---------------- */

    fun loadBackupInfo(mode: String, user: String) {
        val base = serverUrl.value
        if (base.isBlank() || !isAdmin) {
            backupInfo.value = null
            return
        }
        viewModelScope.launch {
            try {
                backupInfo.value = withContext(Dispatchers.IO) { api.backupInfo(base, mode, user) }
            } catch (t: Throwable) {
                backupInfo.value = null
                messages.tryEmit("读取备份信息失败：${t.message ?: "请检查网络"}")
            }
        }
    }

    /** 用系统 DownloadManager 下载：媒体库几十 GB 也不会把 App 压垮。 */
    fun downloadBackup(mode: String, user: String): Boolean {
        if (!isAdmin) {
            messages.tryEmit("只有管理员可以备份")
            return false
        }
        val base = serverUrl.value
        if (base.isBlank()) {
            messages.tryEmit("还没连接服务器")
            return false
        }
        val d = api.backupDownload(base, mode, user)
        val app = getApplication<Application>()
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        val req = DownloadManager.Request(Uri.parse(d.url)).apply {
            d.headers.forEach { (k, v) -> addRequestHeader(k, v) }
            setTitle("听书备份：$mode")
            setDescription(d.fileName)
            setMimeType("application/gzip")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, d.fileName)
        }
        dm.enqueue(req)
        messages.tryEmit("已开始下载备份，完成后到「下载」目录查看")
        return true
    }

    fun restoreBackup(mode: String, user: String, uri: Uri, size: Long) {
        if (!isAdmin) {
            messages.tryEmit("只有管理员可以恢复备份")
            return
        }
        val base = serverUrl.value
        if (base.isBlank()) {
            messages.tryEmit("还没连接服务器")
            return
        }
        viewModelScope.launch {
            backupBusy.value = true
            try {
                val resolver = getApplication<Application>().contentResolver
                val n = api.restoreBackup(base, mode, user, size) { resolver.openInputStream(uri) }
                messages.tryEmit("恢复完成，共 $n 个条目")
            } catch (t: Throwable) {
                messages.tryEmit("恢复失败：${t.message ?: "请检查备份包"}")
            } finally {
                backupBusy.value = false
            }
        }
    }

    /* ---------------- 进度备份 ---------------- */

    fun exportProgress(uri: Uri) {
        viewModelScope.launch {
            val json = ProgressBackup.exportJson(progress.value)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: return@runCatching false
                    stream.bufferedWriter().use { it.write(json) }
                    true
                }.getOrDefault(false)
            }
            messages.tryEmit(if (ok) "进度已导出" else "导出失败")
        }
    }

    fun importProgress(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                messages.tryEmit("读取文件失败")
                return@launch
            }
            val items = ProgressBackup.parse(text)
            if (items == null) {
                messages.tryEmit("不是听书进度备份文件")
                return@launch
            }

            val merged = progress.value.toMutableMap()
            var added = 0
            var updated = 0
            val changed = ArrayList<Pair<String, Progress>>()
            for ((id, rec) in items) {
                val local = merged[id]
                if (local != null && local.updatedAt >= rec.updatedAt) continue
                merged[id] = rec
                if (local == null) added++ else updated++
                changed.add(id to rec)
            }
            if (changed.isEmpty()) {
                messages.tryEmit("没有更新的记录")
                return@launch
            }

            progress.value = merged
            progressStore.save(merged)
            val base = serverUrl.value
            if (base.isNotBlank() && syncEnabled) {
                withContext(Dispatchers.IO) {
                    changed.forEach { (id, p) -> runCatching { api.pushProgress(base, id, p) } }
                }
            }
            messages.tryEmit("导入完成：新增 $added 本，更新 $updated 本")
        }
    }

    /* ---------------- 播放 ---------------- */

    fun playBook(
        book: Book,
        startIndex: Int? = null,
        fromBeginning: Boolean = false,
        positionMs: Long? = null,
    ) {
        // 用最新的 books 状态，避免刚编辑完封面就点播放时拿到旧封面
        val target = books.value.firstOrNull { it.id == book.id } ?: book
        if (target.tracks.isEmpty()) return
        val record = progress.value[target.id]
        val index = (startIndex ?: record?.index ?: 0).coerceIn(0, target.tracks.lastIndex)
        val start = when {
            positionMs != null -> positionMs.coerceAtLeast(0L)
            startIndex != null || fromBeginning -> 0L
            else -> ((record?.time ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
        }

        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { buildMediaItems(target) }
            player.setPlaylist(items, index, start)
            player.setSpeed(prefs.speed)
            player.setVolume(volume.value)
            prefs.lastBookId = target.id
        }
    }

    fun resumeBook(): Book? {
        val last = books.value.firstOrNull { it.id == prefs.lastBookId }
        if (last != null) return last
        val recent = progress.value.entries
            .filter { it.value.isReal }
            .maxByOrNull { it.value.updatedAt }
            ?: return null
        return books.value.firstOrNull { it.id == recent.key }
    }

    fun playAt(index: Int) {
        player.playAt(index)
    }

    fun togglePlayback() {
        if (playerState.value.bookId == null) {
            val book = resumeBook() ?: books.value.firstOrNull() ?: return
            playBook(book)
            return
        }
        player.toggle()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = player.seekBy(deltaMs)
    fun nextChapter() = player.next()
    fun previousChapter() = player.previous()

    fun setSpeed(speed: Float) {
        prefs.speed = speed
        player.setSpeed(speed)
    }

    /* ---------------- 定时关闭 ---------------- */

    fun setSleepMinutes(minutes: Int, finishCurrentChapter: Boolean = false) {
        if (playerState.value.bookId != null) {
            recordPauseMark("timer-start")
        }
        sleep.value = SleepState(
            active = true,
            endsAt = System.currentTimeMillis() + minutes * 60_000L,
            minutes = minutes,
            finishCurrentChapter = finishCurrentChapter,
        )
        armSleep()
        messages.tryEmit(
            if (finishCurrentChapter) "$minutes 分钟后播完本集暂停"
            else "$minutes 分钟后暂停"
        )
    }

    fun setSleepAtChapterEnd() {
        if (playerState.value.bookId != null) {
            recordPauseMark("timer-start")
        }
        sleep.value = SleepState(active = true, atChapterEnd = true)
        sleepJob?.cancel()
        messages.tryEmit("本章播完后暂停")
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleep.value = SleepState()
        messages.tryEmit("已取消定时")
    }

    private fun armSleep() {
        sleepJob?.cancel()
        val current = sleep.value
        if (!current.active || current.atChapterEnd) return
        sleepJob = viewModelScope.launch {
            while (isActive) {
                val left = current.endsAt - System.currentTimeMillis()
                if (left <= 0) {
                    if (current.finishCurrentChapter && playerState.value.isPlaying) {
                        // 到点后不立刻停，等当前这一集播完；onTransition 会处理暂停
                        sleep.value = SleepState(
                            active = true,
                            atChapterEnd = true,
                            finishCurrentChapter = true,
                        )
                        messages.tryEmit("定时到点，本集播完后暂停")
                    } else {
                        addPauseMark("timer", playerState.value)
                        player.pause()
                        sleep.value = SleepState()
                        messages.tryEmit("定时结束，已暂停")
                    }
                    break
                }
                delay(1000)
            }
        }
    }

    /* ---------------- 内部 ---------------- */

    private fun buildMediaItems(book: Book): List<MediaItem> {
        val extras = Bundle().apply {
            putString(PlaybackController.KEY_BOOK_ID, book.id)
            putString(PlaybackController.KEY_BOOK_TITLE, book.title)
            putString(PlaybackController.KEY_COVER_FILE, book.coverFile?.absolutePath.orEmpty())
            putString(PlaybackController.KEY_COVER_URL, book.coverUrl.orEmpty())
        }

        return book.tracks.mapIndexed { index, track ->
            // 通知栏/锁屏封面要和书架一致：优先用 meta 里解出来的本地封面，其次才用文件夹封面
            val artworkUri = book.coverFile?.let { Uri.fromFile(it) }
                ?: book.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(book.title)
                .setAlbumTitle(book.title)
                .setTrackNumber(index + 1)
                .setExtras(extras)
                .apply { artworkUri?.let { setArtworkUri(it) } }
                .build()

            MediaItem.Builder()
                .setMediaId("${book.id}#$index")
                .setUri(track.url)
                .setMediaMetadata(metadata)
                .build()
        }
    }

    private fun humanError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty()
        if (raw.isEmpty()) return "加载失败，请重试"
        return when {
            raw.contains("Failed to connect", true) ||
                raw.contains("Connection refused", true) ||
                raw.contains("ECONNREFUSED", true) -> "连不上服务器，检查地址和路由器状态"
            raw.contains("timeout", true) || raw.contains("timed out", true) ->
                "连接超时，确认手机和路由器在同一个 Wi-Fi"
            raw.startsWith("HTTP") -> "服务器返回错误：$raw"
            else -> raw.take(90)
        }
    }

    private companion object {
        const val MAX_COVER_PUSH_BYTES = 1_000_000L
    }
}
