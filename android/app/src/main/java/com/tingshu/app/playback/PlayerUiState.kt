package com.tingshu.app.playback

/** 播放器对外暴露的 UI 状态，Compose 直接订阅。 */
data class PlayerUiState(
    val ready: Boolean = false,
    val bookId: String? = null,
    val bookTitle: String = "",
    val index: Int = 0,
    val trackTitle: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** 章节切换事件：自动播完 / 用户拖动 / 播放结束。 */
data class Transition(
    val bookId: String,
    val fromIndex: Int,
    val toIndex: Int,
    val reason: Int,
) {
    companion object {
        const val REASON_ENDED = 999
    }
}
