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
                prevBookId = c.currentMediaItem?.mediaMetadata?.extras?.getString(KEY_BOOK_ID)
                prevIndex = c.currentMediaItemIndex
                while (pending.isNotEmpty()) {
                    pending.removeFirst().invoke(c)
                }
                startTicker()
                sync()
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
        _state.value = PlayerUiState(
            ready = true,
            bookId = meta?.extras?.getString(KEY_BOOK_ID),
            bookTitle = meta?.extras?.getString(KEY_BOOK_TITLE) ?: meta?.artist?.toString().orEmpty(),
            index = c.currentMediaItemIndex.coerceAtLeast(0),
            trackTitle = meta?.title?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
            speed = c.playbackParameters.speed,
            volume = c.volume,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    private val playerListener = object : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            sync()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            val newIndex = c.currentMediaItemIndex
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
                val index = c.currentMediaItemIndex
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

    fun setPlaylist(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        withController { c ->
            c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0L))
            c.prepare()
            c.play()
        }
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
        withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previous() {
        withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    fun playAt(index: Int) {
        withController { c ->
            if (index in 0 until c.mediaItemCount) {
                c.seekToDefaultPosition(index)
                c.play()
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
        _state.value = PlayerUiState(ready = true)
    }

    companion object {
        const val KEY_BOOK_ID = "tingshu.bookId"
        const val KEY_BOOK_TITLE = "tingshu.bookTitle"
        const val KEY_COVER_FILE = "tingshu.coverFile"
        const val KEY_COVER_URL = "tingshu.coverUrl"
    }
}
