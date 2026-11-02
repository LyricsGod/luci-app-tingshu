package com.tingshu.app.ui.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.PauseMark
import com.tingshu.app.data.ProgressBackup
import com.tingshu.app.data.UploadState
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.formatTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 上传书籍：选整个文件夹，或者选 zip / tar.gz。 */
@Composable
fun UploadDialog(vm: TingshuViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by vm.uploadState.collectAsStateWithLifecycle()
    val root by vm.libraryRoot.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.uploadFolder(uri)
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.uploadArchive(uri, displayNameOf(context, uri))
        }
    }

    TingshuDialog(title = "上传书籍", onDismiss = onDismiss) {
        val running = state is UploadState.Running
        Text(
            text = "上传到 ${root.ifBlank { "书架目录" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UploadCard(
                title = "选择文件夹",
                subtitle = "整个书文件夹，可一次传多本",
                icon = Icons.Rounded.Folder,
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { folderPicker.launch(null) }
            UploadCard(
                title = "选择压缩包",
                subtitle = "zip / tar.gz / tgz",
                icon = Icons.Rounded.Archive,
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { archivePicker.launch(arrayOf("*/*")) }
        }

        Spacer(Modifier.height(14.dp))
        when (val s = state) {
            is UploadState.Running -> {
                val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (s.total > 0) {
                        "正在上传 ${s.done}/${s.total} · ${formatBytes(s.bytes)} · ${s.label}"
                    } else {
                        s.label
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            is UploadState.Done -> {
                Text(
                    text = if (s.failed > 0) "完成：成功 ${s.imported} 个，失败 ${s.failed} 个" else "已导入 ${s.imported} 个文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is UploadState.Error -> {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            UploadState.Idle -> Unit
        }

        Text(
            text = "文件夹名就是书名；封面放 cover.jpg 会自动识别。支持 mp3 / m4a / m4b / aac / ogg / flac / wav。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        if (state is UploadState.Done || state is UploadState.Error) {
            DialogFooter {
                TextButton(onClick = {
                    vm.resetUploadState()
                    onDismiss()
                }) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun UploadCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

/** 进度备份：导出 / 导入 JSON，格式和网页端一致。 */
@Composable
fun BackupDialog(vm: TingshuViewModel, onDismiss: () -> Unit) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val count = progress.values.count { it.isReal }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportProgress(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProgress(uri)
    }

    TingshuDialog(title = "进度备份", onDismiss = onDismiss) {
        Text(
            text = if (count > 0) "当前有 $count 本书留下了听书记录，可以导出存档。" else "还没有任何听书记录，先听一段再回来导出。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UploadCard(
                title = "导出进度",
                subtitle = "存成一个 JSON 文件",
                icon = Icons.Rounded.Download,
                enabled = count > 0,
                modifier = Modifier.weight(1f),
            ) { createFile.launch(ProgressBackup.fileName()) }
            UploadCard(
                title = "导入进度",
                subtitle = "从备份文件恢复",
                icon = Icons.Rounded.Upload,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) { openFile.launch(arrayOf("*/*")) }
        }
        Text(
            text = "进度平时存在路由器上，各端自动同步。这个文件用于整体搬家；导入时同一本书取较新的那份，不会把新的覆盖成旧的。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 暂停记录：点击续播、单条删除、清空。 */
@Composable
fun PauseMarksDialog(
    vm: TingshuViewModel,
    bookId: String?,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    val all by vm.pauseMarks.collectAsStateWithLifecycle()
    val marks = remember(all, bookId) {
        all.filter { bookId == null || it.bookId == bookId }.sortedByDescending { it.createdAt }
    }
    var confirmClear by remember { mutableStateOf(false) }

    TingshuDialog(
        title = if (bookId == null) "暂停记录" else "本书的暂停记录",
        onDismiss = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (marks.isEmpty()) "还没有记录" else "${marks.size} 条记录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (marks.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("清空全部", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (marks.isEmpty()) {
            Text(
                text = "播放中暂停、或开启定时，都会自动记一笔。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                items(marks, key = { it.id }) { mark ->
                    PauseMarkRow(
                        mark = mark,
                        showBook = bookId == null,
                        onClick = {
                            vm.playFromMark(mark)
                            onJump()
                            onDismiss()
                        },
                        onDelete = { vm.deletePauseMark(mark.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空这些暂停记录？") },
            text = { Text("会删除 ${marks.size} 条记录，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearPauseMarks(bookId)
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PauseMarkRow(
    mark: PauseMark,
    showBook: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (showBook) mark.bookTitle.ifBlank { "未知书籍" } else mark.trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (showBook) {
                        append(mark.trackTitle)
                        append(" · ")
                    }
                    append(formatTime(mark.time))
                    append(" · ")
                    append(reasonLabel(mark.reason))
                    append(" · ")
                    append(stamp(mark.createdAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除这条",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun reasonLabel(reason: String): String = when (reason) {
    "timer" -> "定时暂停"
    "timer-start" -> "开启定时"
    else -> "暂停"
}

private fun stamp(ts: Long): String =
    SimpleDateFormat("M-d HH:mm", Locale.getDefault()).format(Date(ts))

private fun displayNameOf(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment ?: "archive.zip"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}
