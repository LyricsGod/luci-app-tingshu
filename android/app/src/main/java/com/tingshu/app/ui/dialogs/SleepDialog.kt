package com.tingshu.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.tingshu.app.ui.SleepState

/** 定时关闭可选的分钟数。 */
val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

/**
 * 定时关闭弹窗。
 *
 * 播放界面和迷你播放器共用这一份，选项、顺序、选中态都只有一处定义，
 * 避免两边各写一套导致不一致。选完自动关闭，取消由调用方清理状态。
 */
@Composable
fun SleepDialog(
    sleep: SleepState,
    onDismiss: () -> Unit,
    onMinutes: (Int, Boolean) -> Unit,
    onChapterEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    var finishChapter by remember(sleep.active, sleep.finishCurrentChapter) {
        mutableStateOf(sleep.finishCurrentChapter)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("到点后播完本集再暂停", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "计时结束时先听完当前这一集",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = finishChapter,
                        onCheckedChange = { finishChapter = it },
                    )
                }

                Spacer(Modifier.height(6.dp))

                SLEEP_MINUTES.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onMinutes(minutes, finishChapter)
                                onDismiss()
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = sleep.active && !sleep.atChapterEnd && sleep.minutes == minutes,
                            onClick = {
                                onMinutes(minutes, finishChapter)
                                onDismiss()
                            },
                        )
                        Text("$minutes 分钟", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onChapterEnd()
                            onDismiss()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = sleep.active && sleep.atChapterEnd,
                        onClick = {
                            onChapterEnd()
                            onDismiss()
                        },
                    )
                    Text("本章播完后暂停", style = MaterialTheme.typography.bodyLarge)
                }

                if (sleep.active) {
                    TextButton(onClick = {
                        onCancel()
                        onDismiss()
                    }) {
                        Text("关闭定时", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
