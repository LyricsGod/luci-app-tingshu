package com.tingshu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.Book
import com.tingshu.app.data.Progress
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.bookDoneCount
import com.tingshu.app.ui.components.bookFraction
import com.tingshu.app.ui.components.bookPercent
import com.tingshu.app.ui.components.formatTime
import com.tingshu.app.ui.dialogs.AddToCollectionDialog
import com.tingshu.app.ui.dialogs.EditBookDialog
import com.tingshu.app.ui.dialogs.PauseMarksDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    vm: TingshuViewModel,
    book: Book,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress by vm.progress.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()

    val record = progress[book.id]
    val isCurrent = playerState.bookId == book.id
    val playing = isCurrent && playerState.isPlaying
    val doneCount = bookDoneCount(book, record)
    val hasProgress = record?.isReal == true
    val collection = collections.firstOrNull { book.id in it.bookIds }

    var showEdit by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun start(fromBeginning: Boolean = false) {
        vm.playBook(book, fromBeginning = fromBeginning)
        onOpenPlayer()
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = book.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (collection == null) "加入合集" else "合集：${collection.name}") },
                                onClick = {
                                    menuOpen = false
                                    showCollections = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑资料") },
                                onClick = {
                                    menuOpen = false
                                    showEdit = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("暂停记录") },
                                onClick = {
                                    menuOpen = false
                                    showMarks = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从头开始") },
                                onClick = {
                                    menuOpen = false
                                    start(fromBeginning = true)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                    navigationIconContentColor = colors.onBackground,
                    actionIconContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .width(112.dp)
                            .height(112.dp)
                            .shadow(12.dp, RoundedCornerShape(16.dp)),
                        corner = 16.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append("${book.chapterCount} 章")
                                if (hasProgress) {
                                    append(" · 已听 ")
                                    append(formatTime(record?.time ?: 0.0))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        if (doneCount > 0) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "已听完 $doneCount 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (collection != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = collection.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.primary,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (isCurrent) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(colors.primary)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (playing) "正在播放" else "已暂停",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { bookFraction(book, record) },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = colors.primary,
                        trackColor = colors.outlineVariant,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = bookPercent(book, record),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (playing) {
                                vm.togglePlayback()
                            } else {
                                if (isCurrent) vm.togglePlayback() else vm.playBook(book)
                                onOpenPlayer()
                            }
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = when {
                                playing -> "暂停播放"
                                hasProgress -> "继续 · 第 ${(record?.index ?: 0) + 1} 章"
                                else -> "开始播放"
                            },
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { start(fromBeginning = true) },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(Icons.Rounded.Replay, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("从头", fontSize = 12.sp)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { showMarks = true }) {
                        Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("暂停记录", fontSize = 12.sp)
                    }
                    TextButton(onClick = { showEdit = true }) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑资料", fontSize = 12.sp)
                    }
                    TextButton(onClick = { showCollections = true }) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (collection == null) "加入合集" else "合集", fontSize = 12.sp)
                    }
                }
            }

            if (book.description.isNotBlank()) {
                item {
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        lineHeight = 22.sp,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
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
            }

            itemsIndexed(book.tracks) { index, track ->
                val isPlayingThis = isCurrent && playerState.index == index
                val done = record?.done?.contains(index) == true
                ChapterRow(
                    index = index,
                    title = track.title,
                    current = isPlayingThis,
                    done = done,
                    playing = isPlayingThis && playing,
                    onClick = {
                        vm.playBook(book, startIndex = index, fromBeginning = true)
                        onOpenPlayer()
                    },
                )
            }
        }
    }

    if (showEdit) {
        EditBookDialog(vm = vm, book = book, onDismiss = { showEdit = false })
    }
    if (showMarks) {
        PauseMarksDialog(
            vm = vm,
            bookId = book.id,
            onJump = onOpenPlayer,
            onDismiss = { showMarks = false },
        )
    }
    if (showCollections) {
        AddToCollectionDialog(vm = vm, book = book, onDismiss = { showCollections = false })
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    current: Boolean,
    done: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (current) colors.primaryContainer else colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (done && !current) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(15.dp),
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) colors.primary else colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (current) {
                Text(
                    text = if (playing) "正在播放" else "已暂停",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
        }
        if (current) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Suppress("unused")
private fun chapterLabel(book: Book, record: Progress?): String {
    val index = (record?.index ?: 0).coerceIn(0, (book.chapterCount - 1).coerceAtLeast(0))
    return "第 ${index + 1} / ${book.chapterCount} 章"
}
