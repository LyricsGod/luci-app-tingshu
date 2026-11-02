package com.tingshu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingshu.app.data.Book
import com.tingshu.app.playback.PlayerUiState
import com.tingshu.app.ui.SleepState
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.formatSpeed
import com.tingshu.app.ui.components.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    book: Book?,
    state: PlayerUiState,
    sleep: SleepState,
    syncEnabled: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSleepMinutes: (Int, Boolean) -> Unit,
    onSleepChapterEnd: () -> Unit,
    onCancelSleep: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onVolume: (Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    if (book == null) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = colors.outline,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有在播放的书", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("返回书架") }
        }
        return
    }

    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var finishChapter by remember(sleep.active, sleep.finishCurrentChapter) {
        mutableStateOf(sleep.finishCurrentChapter)
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(sleep.active, sleep.endsAt) {
        if (!sleep.active) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val durationSec = state.durationMs / 1000f
    val maxSec = if (durationSec > 1f) durationSec else 1f
    val shownSec = if (dragging) dragValue else (state.positionMs / 1000f).coerceIn(0f, maxSec)
    val chapterIndex = (state.index + 1).coerceIn(1, book.chapterCount)

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.primary.copy(alpha = 0.16f), colors.background, colors.background)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp)
                .padding(top = 6.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "收起", tint = colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "正在播放",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showChapters = true }) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, "目录", tint = colors.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(14.dp))
            BookCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .shadow(20.dp, RoundedCornerShape(22.dp)),
                corner = 22.dp,
            )
            Spacer(Modifier.height(22.dp))

            Text(
                text = state.trackTitle.ifBlank { book.tracks.getOrNull(state.index)?.title.orEmpty() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "第 $chapterIndex 章 / 共 ${book.chapterCount} 章",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )

            Spacer(Modifier.height(10.dp))
            Slider(
                value = shownSec,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeekTo((dragValue * 1000f).toLong())
                    dragging = false
                },
                valueRange = 0f..maxSec,
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.outlineVariant,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = formatTime(shownSec.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (durationSec > 1f) formatTime(durationSec.toDouble()) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, enabled = state.hasPrevious, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "上一章",
                        tint = if (state.hasPrevious) colors.onSurface else colors.outline,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(
                    onClick = { onSeekBy(-30_000L) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay30,
                        contentDescription = "后退 30 秒",
                        tint = colors.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = colors.onPrimary,
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(
                    onClick = { onSeekBy(30_000L) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward30,
                        contentDescription = "前进 30 秒",
                        tint = colors.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "下一章",
                        tint = if (state.hasNext) colors.onSurface else colors.outline,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showSpeed = true }) {
                    Text(
                        text = formatSpeed(state.speed),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )
                }
                TextButton(onClick = { showSleep = true }) {
                    Text(
                        text = sleepLabel(sleep, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sleep.active) colors.primary else colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showChapters = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "目录",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "音量",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Slider(
                    value = state.volume.coerceIn(0f, 1f),
                    onValueChange = onVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.outlineVariant,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))
            Text(
                text = if (syncEnabled) "进度已同步到路由器 · 换台设备也能接着听" else "进度只保存在本机",
                style = MaterialTheme.typography.labelSmall,
                color = colors.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("播放速度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.chunked(3).forEach { rowSpeeds ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowSpeeds.forEach { speed ->
                                FilterChip(
                                    selected = abs(state.speed - speed) < 0.01f,
                                    onClick = {
                                        onSelectSpeed(speed)
                                        showSpeed = false
                                    },
                                    label = { Text(formatSpeed(speed), maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.primaryContainer,
                                        selectedLabelColor = colors.primary,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeed = false }) { Text("关闭") }
            },
        )
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("定时关闭") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
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
                                    onSleepMinutes(minutes, finishChapter)
                                    showSleep = false
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sleep.active && !sleep.atChapterEnd && sleep.minutes == minutes,
                                onClick = {
                                    onSleepMinutes(minutes, finishChapter)
                                    showSleep = false
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
                                onSleepChapterEnd()
                                showSleep = false
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = sleep.active && sleep.atChapterEnd,
                            onClick = {
                                onSleepChapterEnd()
                                showSleep = false
                            },
                        )
                            Text("本章播完后暂停", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (sleep.active) {
                        TextButton(
                            onClick = {
                                onCancelSleep()
                                showSleep = false
                            },
                        ) {
                            Text("关闭定时", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleep = false }) { Text("取消") }
            },
        )
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            containerColor = colors.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("目录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${book.chapterCount} 章",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    itemsIndexed(book.tracks) { index, track ->
                        val active = index == state.index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectChapter(index)
                                    showChapters = false
                                }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) colors.primary else colors.onSurfaceVariant,
                                modifier = Modifier.width(30.dp),
                            )
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active) colors.primary else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (active) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sleepLabel(sleep: SleepState, now: Long): String {
    if (!sleep.active) return "定时"
    if (sleep.atChapterEnd) {
        return if (sleep.finishCurrentChapter) "本集后停" else "本章后停"
    }
    val leftSeconds = ((sleep.endsAt - now) / 1000L).coerceAtLeast(0L)
    val text = when {
        leftSeconds >= 60 -> "${leftSeconds / 60} 分钟后停"
        leftSeconds > 0 -> "${leftSeconds} 秒后停"
        else -> "即将暂停"
    }
    return if (sleep.finishCurrentChapter) text.replace("后停", "后本集停") else text
}
