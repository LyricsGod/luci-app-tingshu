package com.tingshu.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面与 [PlaybackService] 之间的桥：连上 MediaSession，向 Compose 暴露状态，
 * 并把「上一章播完了」这类事件单独发出去，让 ViewModel 记进度。
 *
 * 播放列表采用滑动窗口：一次只把当前章节附近的几十章交给 MediaSession。
 * 以前一本 2813 章的书要构造 2813 个 MediaItem（每个都带 Bundle + Metadata），
 * 再整体跨 Binder 塞给服务——既慢又可能撞上 1MB 事务上限。
 * 窗口对外仍使用「全局章节下标」，UI / 进度 / 暂停记录都不需要知道窗口的存在。
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _transitions = MutableSharedFlow<Transition>(extraBufferCapacity = 8)
    val transitions: SharedFlow<Transition> = _transitions.asSharedFlow()

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private var prevIndex = -1
    private var prevBookId: String? = null
    private var ticker: Job? = null
    private var retryCount = 0

    /* ---------- 滑动窗口 ---------- */
    private var activeBookId: String? = null
    private var totalChapters = 0
    private var windowStart = 0
    private var itemFactory: ((Int) -> MediaItem)? = null

    init {
        connect()
    }

    private fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c != null) {
                retryCount = 0
                controller = c
                c.addListener(playerListener)
                restoreWindowState(c)
                startTicker()
                sync()
                // 控制器连上之前用户可能已经点了播放；这些命令排在 pending 里，
                // 必须放最后执行，让它们设置的窗口状态不被 restoreWindowState 覆盖。
                while (pending.isNotEmpty()) {
                    pending.removeFirst().invoke(c)
                }
            } else {
                future = null
                if (retryCount < 5) {
                    retryCount++
                    scope.launch {
                        delay(800)
                        connect()
                    }
                }
            }
        }, mainExecutor)
    }

    /**
     * App 重启后 MediaSession 可能还留着上次的播放列表；那时 itemFactory 是空的，
     * 只能用 Media3 自己的下标当全局下标。等用户点播放，setPlaylist 会重建窗口。
     */
    private fun restoreWindowState(c: MediaController) {
        val bookId = c.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_BOOK_ID)
        if (bookId == null || bookId != activeBookId) {
            windowStart = 0
            totalChapters = c.mediaItemCount
        }
        prevBookId = bookId
        prevIndex = globalIndexOf(c.currentMediaItemIndex)
    }

    /** Media3 的局部下标 → 全书章节下标。 */
    private fun globalIndexOf(local: Int): Int {
        if (local < 0) return 0
        // 没有活跃窗口（服务恢复的旧列表）时直接沿用局部下标
        if (activeBookId == null || totalChapters == 0) return local
        return (windowStart + local).coerceIn(0, totalChapters - 1)
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) sync()
                delay(500)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        val meta = c.currentMediaItem?.mediaMetadata
        val duration = c.duration
        val local = c.currentMediaItemIndex.coerceAtLeast(0)
        val global = globalIndexOf(local)
        val bookId = meta?.extras?.getString(KEY_BOOK_ID)
        // 走到窗口尾部时 quietly 往后接一窗，用户不会在章节边界卡一下
        if (bookId == activeBookId && c.isPlaying) extendWindow(c)
        _state.value = PlayerUiState(
            ready = true,
            bookId = bookId,
            bookTitle = meta?.extras?.getString(KEY_BOOK_TITLE) ?: meta?.artist?.toString().orEmpty(),
            index = global,
            trackTitle = meta?.title?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
            speed = c.playbackParameters.speed,
            volume = c.volume,
            // 用全书章数判断，而不是 Media3 的窗口边界；否则窗口末端会误显示「没有下一章」
            hasNext = bookId == activeBookId && global + 1 < totalChapters || c.hasNextMediaItem(),
            hasPrevious = global > 0,
        )
    }

    /** 离窗口末尾不到 6 章时，把后面一窗追加上去。 */
    private fun extendWindow(c: MediaController) {
        val factory = itemFactory ?: return
        if (totalChapters <= 0) return
        val windowEnd = windowStart + c.mediaItemCount
        if (globalIndexOf(c.currentMediaItemIndex) + 6 < windowEnd) return
        val from = windowEnd
        val to = (from + WINDOW).coerceAtMost(totalChapters)
        if (from >= to) return
        c.addMediaItems((from until to).map { factory(it) })
    }

    private val playerListener = object : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            sync()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            val newIndex = globalIndexOf(c.currentMediaItemIndex)
            val bookId = c.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_BOOK_ID)
            if (bookId != null && bookId == prevBookId && prevIndex >= 0 && newIndex != prevIndex) {
                _transitions.tryEmit(Transition(bookId, prevIndex, newIndex, reason))
            }
            prevBookId = bookId
            prevIndex = newIndex
            sync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_ENDED) {
                val bookId = c.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_BOOK_ID)
                val index = globalIndexOf(c.currentMediaItemIndex)
                if (bookId != null && index >= 0) {
                    _transitions.tryEmit(Transition(bookId, index, index, Transition.REASON_ENDED))
                }
            }
            sync()
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            action(c)
        } else {
            pending.addLast(action)
            connect()
        }
    }

    /**
     * 设置播放列表。只构建当前章节附近的一窗 MediaItem，不再一次性构造全书。
     *
     * @param index 全书章节下标（不是窗口内下标）
     * @param itemFactory 按全局下标惰性构造 MediaItem
     */
    fun setPlaylist(
        bookId: String,
        total: Int,
        index: Int,
        startPositionMs: Long,
        itemFactory: (Int) -> MediaItem,
    ) {
        if (total <= 0) return
        activeBookId = bookId
        totalChapters = total
        this.itemFactory = itemFactory
        val start = index.coerceIn(0, total - 1)
        val from = (start - LOOK_BACK).coerceAtLeast(0)
        val to = (from + WINDOW).coerceAtMost(total)
        windowStart = from
        val items = (from until to).map(itemFactory)
        prevBookId = bookId
        prevIndex = start
        withController { c ->
            c.setMediaItems(items, start - from, startPositionMs.coerceAtLeast(0L))
            c.prepare()
            c.play()
        }
    }

    /** 重建窗口并定位到指定的全书章节；只在跨窗口跳转时调用。 */
    private fun rebuildAround(index: Int, c: MediaController, play: Boolean) {
        val factory = itemFactory ?: return
        if (totalChapters <= 0) return
        val target = index.coerceIn(0, totalChapters - 1)
        val from = (target - LOOK_BACK).coerceAtLeast(0)
        val to = (from + WINDOW).coerceAtMost(totalChapters)
        windowStart = from
        prevIndex = target
        c.setMediaItems((from until to).map(factory), target - from, 0L)
        c.prepare()
        if (play) c.play()
    }

    fun play() {
        withController { c ->
            if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) {
                c.prepare()
            }
            c.play()
        }
    }

    fun pause() {
        withController { it.pause() }
    }

    fun toggle() {
        withController { c ->
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) {
                    c.prepare()
                }
                c.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    fun seekBy(deltaMs: Long) {
        withController { c ->
            val duration = c.duration
            val max = if (duration > 0 && duration != C.TIME_UNSET) duration else Long.MAX_VALUE
            c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, max))
        }
    }

    fun next() {
        withController { c ->
            extendWindow(c)
            if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        }
    }

    fun previous() {
        withController { c ->
            if (c.hasPreviousMediaItem()) {
                c.seekToPreviousMediaItem()
            } else {
                val global = globalIndexOf(c.currentMediaItemIndex)
                if (global > 0) rebuildAround(global - 1, c, c.isPlaying)
            }
        }
    }

    /** @param index 全书章节下标 */
    fun playAt(index: Int) {
        withController { c ->
            val local = index - windowStart
            if (local in 0 until c.mediaItemCount) {
                c.seekToDefaultPosition(local)
                if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) {
                    c.prepare()
                }
                prevIndex = index
                c.play()
            } else {
                rebuildAround(index, c, play = true)
            }
        }
    }

    fun setSpeed(speed: Float) {
        withController { it.setPlaybackSpeed(speed.coerceIn(0.5f, 3f)) }
    }

    fun setVolume(volume: Float) {
        withController { it.volume = volume.coerceIn(0f, 1f) }
    }

    /** 停止并清空播放列表（删除正在播的书时用）。 */
    fun stop() {
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
        activeBookId = null
        totalChapters = 0
        windowStart = 0
        itemFactory = null
        _state.value = PlayerUiState(ready = true)
    }

    companion object {
        private const val WINDOW = 40
        private const val LOOK_BACK = 8

        const val KEY_BOOK_ID = "tingshu.bookId"
        const val KEY_BOOK_TITLE = "tingshu.bookTitle"
        const val KEY_COVER_FILE = "tingshu.coverFile"
        const val KEY_COVER_URL = "tingshu.coverUrl"
    }
}
