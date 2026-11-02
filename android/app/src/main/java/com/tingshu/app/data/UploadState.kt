package com.tingshu.app.data

/** 上传/导入的界面状态。 */
sealed interface UploadState {
    data object Idle : UploadState
    data class Running(
        val done: Int = 0,
        val total: Int = 0,
        val bytes: Long = 0L,
        val label: String = "",
    ) : UploadState
    data class Done(val imported: Int, val failed: Int) : UploadState
    data class Error(val message: String) : UploadState
}
