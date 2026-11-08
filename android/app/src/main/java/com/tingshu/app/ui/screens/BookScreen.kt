package com.tingshu.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.Book
import com.tingshu.app.data.Progress
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.BookCover
import com.tingshu.app.ui.components.bookDoneCount
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
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
    val bookRefreshing by vm.bookRefreshing.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val authRole by vm.authRole.collectAsStateWithLifecycle()
    val isAdmin = authRole == "admin"

    val record = progress[book.id]
    val isCurrent = playerState.bookId == book.id
    val playing = isCurrent && playerState.isPlaying
    val doneCount = bookDoneCount(book, record)
    val hasProgress = record?.isReal == true
    val collection = collections.firstOrNull { book.id in it.bookIds }

    // 每章时长（服务端算好）→ 已听 X 小时 / 全书 Y 小时
    val bookDurations by vm.bookDurations.collectAsStateWithLifecycle()
    LaunchedEffect(book.id) { vm.loadDurations(book) }
    val durationByPath = if (bookDurations.bookId == book.id) bookDurations.items else emptyMap()
    // 少数文件可能解析不出时长（格式不符、损坏）。这种情况用已有时长的平均值补齐，
    // 否则整本书会因为缺一章而失去「剩余 X 小时」。
    val knownDurations = remember(book.id, durationByPath) {
        book.tracks.count { durationByPath.containsKey(it.path) }
    }
    val totalSeconds = remember(book.id, durationByPath, knownDurations) {
        if (knownDurations == 0) {
            0.0
        } else {
            val knownSum = book.tracks.sumOf { durationByPath[it.path] ?: 0.0 }
            val missing = book.tracks.size - knownDurations
            knownSum + knownSum / knownDurations * missing
        }
    }
    // 至少一半章节有真实时长才展示；缺得太多时估算不可信，退回「已听 N/M 章」
    val hoursReady = knownDurations > 0 && knownDurations * 2 >= book.tracks.size

    var showEdit by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnread by remember { mutableStateOf(false) }
    // 点封面才弹出简介，书籍页不再常驻显示
    var showDescription by remember { mutableStateOf(false) }
    // 书籍页小字第二行：默认显示剩余时长，点一下切成已听/共
    var showListened by rememberSaveable(book.id) { mutableStateOf(false) }
    // 章节多选：批量标已读 / 未读
    var selecting by rememberSaveable(book.id) { mutableStateOf(false) }
    var selected by remember(book.id) { mutableStateOf(setOf<Int>()) }

    val chapterListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var ascending by rememberSaveable(book.id) { mutableStateOf(true) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    val displayIndices: List<Int> = remember(book.tracks, ascending) {
        if (ascending) book.tracks.indices.toList() else book.tracks.indices.reversed().toList()
    }
    // 章节列表已搬进独立容器，列表里只剩章节项，不必再扣掉头部的 item 数
    val visibleChapterPos = chapterListState.firstVisibleItemIndex
        .coerceIn(0, (book.chapterCount - 1).coerceAtLeast(0))
    val visibleChapterNumber = displayIndices.getOrNull(visibleChapterPos)?.plus(1) ?: 1

    // 打开书籍时把目录定位到「继续收听」的那一章；切换正/倒序时再定位一次
    var resumeLocated by rememberSaveable(book.id) { mutableStateOf(false) }
    fun resumeChapterPos(): Int {
        val idx = (if (isCurrent) playerState.index else (record?.index ?: 0))
            .coerceIn(0, (book.chapterCount - 1).coerceAtLeast(0))
        return if (ascending) idx else (book.chapterCount - 1 - idx)
    }
    LaunchedEffect(book.id, record?.index) {
        if (resumeLocated || book.chapterCount <= 0) return@LaunchedEffect
        chapterListState.scrollToItem(resumeChapterPos())
        resumeLocated = true
    }
    LaunchedEffect(ascending) {
        if (!resumeLocated || book.chapterCount <= 0) return@LaunchedEffect
        chapterListState.scrollToItem(resumeChapterPos())
    }

    fun start(fromBeginning: Boolean = false) {
        vm.playBook(book, fromBeginning = fromBeginning)
        // 播放后留在书籍页，让迷你播放器从下方滑入，不再跳全屏播放器
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
                                text = { Text("全本已读") },
                                enabled = book.chapterCount > 0,
                                onClick = {
                                    menuOpen = false
                                    vm.markAllChaptersRead(book)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("全本未读") },
                                enabled = book.chapterCount > 0,
                                onClick = {
                                    menuOpen = false
                                    confirmUnread = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从头开始") },
                                onClick = {
                                    menuOpen = false
                                    start(fromBeginning = true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (collection == null) "加入合集" else "合集：${collection.name}") },
                                onClick = {
                                    menuOpen = false
                                    showCollections = true
                                },
                            )
                            if (isAdmin) {
                                DropdownMenuItem(
                                    text = { Text("编辑资料") },
                                    onClick = {
                                        menuOpen = false
                                        showEdit = true
                                    },
                                )
                            }
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
        PullToRefreshBox(
            isRefreshing = bookRefreshing,
            onRefresh = { vm.refreshBookData() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Box(Modifier.fillMaxSize()) {
        // 头部资料按内容高度占一块，目录容器吃掉剩余高度贴到页面底部。
        // BoxWithConstraints 的 maxHeight 已经扣掉外层 Scaffold 的 MiniPlayer 高度，
        // 有迷你播放器时目录会自动变矮，不需要写死数值。
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // 先在外层取可用高度：ColumnScope 里直接用 maxHeight 会和隐式 receiver 冲突
        val availableHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (availableHeight - 220.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(bottom = if (selecting) 118.dp else 8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .width(112.dp)
                            .height(112.dp)
                            .shadow(12.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showDescription = true },
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
                        val shownIndex = if (isCurrent) playerState.index else (record?.index ?: 0)
                        val shownPos = if (isCurrent) playerState.positionMs / 1000.0 else (record?.time ?: 0.0)
                        val shownDur = if (isCurrent) playerState.durationMs / 1000.0 else 0.0
                        val doneSet = record?.done?.toSet() ?: emptySet()
                        val listenedSeconds = if (hoursReady) {
                            book.tracks.indices.sumOf { i ->
                                val d = durationByPath[book.tracks[i].path] ?: 0.0
                                when {
                                    doneSet.contains(i) -> d
                                    i == shownIndex -> min(d, shownPos)
                                    else -> 0.0
                                }
                            }
                        } else 0.0

                        // 共几章
                        Text(
                            text = "共 ${book.chapterCount} 章",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        // 剩余时长 / 已听时长：点一下切换（默认显示剩余）
                        if (hoursReady) {
                            val remainingSeconds = (totalSeconds - listenedSeconds).coerceAtLeast(0.0)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = when {
                                    showListened -> "已听 ${fmtHours(listenedSeconds)} 小时/共 ${fmtHours(totalSeconds)} 小时"
                                    remainingSeconds < 60 -> "已听完"
                                    else -> "剩余 ${fmtHours(remainingSeconds)} 小时"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showListened = !showListened }
                                    .padding(vertical = 2.dp),
                            )
                        } else if (hasProgress) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "已听 $doneCount/${book.chapterCount} 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        // 当前听到哪一章、本章进度（播放状态单独一行，和上个版本一致）
                        if (hasProgress) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = buildString {
                                    append(if (isCurrent) "正在收听 " else "上次听到 ")
                                    append(shownIndex + 1)
                                    append(" 章 ")
                                    append(formatTime(shownPos))
                                    if (shownDur > 1) {
                                        append("/")
                                        append(formatTime(shownDur))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                // 继续播放只唤起迷你播放器，留在书籍页，不跳全屏播放器页
                                if (isCurrent) vm.togglePlayback() else vm.playBook(book)
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
                        onClick = { showMarks = true },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(Icons.Rounded.Timer, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("暂停记录", fontSize = 12.sp)
                    }
                }
            }

            // 工具项已收进右上角菜单；简介改为点封面弹出，书籍页不再常驻占地方
        }   // 头部 LazyColumn 结束：上面只放书籍资料，不再放章节

        // 目录容器：weight(1f) 吃掉剩余高度，直接贴到页面底部
        ChapterPanel(
            modifier = Modifier.fillMaxWidth().weight(1f),
            count = book.chapterCount,
            selecting = selecting,
            onToggleSelect = {
                selecting = !selecting
                selected = emptySet()
            },
            ascending = ascending,
            onToggleOrder = { ascending = !ascending },
            listState = chapterListState,
            visiblePos = visibleChapterPos,
            visibleNumber = visibleChapterNumber,
            onSeek = { target ->
                seekJob?.cancel()
                seekJob = scope.launch {
                    chapterListState.scrollToItem(
                        target.coerceIn(0, (book.chapterCount - 1).coerceAtLeast(0))
                    )
                }
            },
        ) {
            itemsIndexed(displayIndices, key = { _, actualIndex -> actualIndex }) { _, actualIndex ->
                val track = book.tracks[actualIndex]
                val isPlayingThis = isCurrent && playerState.index == actualIndex
                val done = record?.done?.contains(actualIndex) == true
                ChapterRow(
                    index = actualIndex,
                    title = track.title,
                    current = isPlayingThis,
                    done = done,
                    playing = isPlayingThis && playing,
                    selecting = selecting,
                    selected = selected.contains(actualIndex),
                    onClick = {
                        if (selecting) {
                            // 多选时点章节只是勾选，不播放
                            selected = if (selected.contains(actualIndex)) selected - actualIndex else selected + actualIndex
                        } else {
                            vm.playBook(book, startIndex = actualIndex, fromBeginning = true)
                            onOpenPlayer()
                        }
                    },
                )
            }
        }
        }   // Column
        }   // BoxWithConstraints

            // 章节定位条已随目录一起搬进 ChapterPanel，只覆盖目录卡片，不再贯穿整页

            if (selecting) {
                SelectionBar(
                    selectedCount = selected.size,
                    total = book.chapterCount,
                    onToggleAll = {
                        selected = if (selected.size >= book.chapterCount) {
                            emptySet()
                        } else {
                            book.tracks.indices.toSet()
                        }
                    },
                    onMarkRead = {
                        vm.setChaptersDone(book, selected, true)
                        selected = emptySet()
                        selecting = false
                    },
                    onMarkUnread = {
                        vm.setChaptersDone(book, selected, false)
                        selected = emptySet()
                        selecting = false
                    },
                    onClose = {
                        selecting = false
                        selected = emptySet()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        }
    }

    // 点封面弹出简介：把原来常驻的一大段文字收起来，给目录多留空间
    if (showDescription) {
        AlertDialog(
            onDismissRequest = { showDescription = false },
            title = { Text(book.title) },
            text = {
                Text(
                    text = book.description.ifBlank { "这本书还没有简介，可以在右上角菜单的「编辑资料」里补一段。" },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDescription = false }) { Text("关闭") }
            },
        )
    }

    if (confirmUnread) {
        AlertDialog(
            onDismissRequest = { confirmUnread = false },
            title = { Text("全本标为未读") },
            text = {
                Text("把《${book.title}》全部 ${book.chapterCount} 章标为未读，并清除收听位置？")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnread = false
                    vm.markAllChaptersUnread(book)
                }) { Text("标为未读") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnread = false }) { Text("取消") }
            },
        )
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

/**
 * 选集目录独立容器：目录标题、章节列表、章节定位滚动条都收进这一张卡片。
 * 卡片高度固定，章节只在卡片内部滚动，滚动条也只覆盖卡片本身；
 * 上方的书籍资料不会被一起卷走，也不会被右侧透明手势层挡住。
 */
@Composable
private fun ChapterPanel(
    modifier: Modifier = Modifier,
    count: Int,
    selecting: Boolean,
    onToggleSelect: () -> Unit,
    ascending: Boolean,
    onToggleOrder: () -> Unit,
    listState: LazyListState,
    visiblePos: Int,
    visibleNumber: Int,
    onSeek: (Int) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("目录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$count 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggleSelect) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(if (selecting) "完成" else "多选", fontSize = 12.sp)
                }
                TextButton(onClick = onToggleOrder) {
                    Icon(Icons.Rounded.SwapVert, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(if (ascending) "倒序" else "正序", fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = 34.dp, bottom = 8.dp),
                    content = content,
                )
                if (count > 3) {
                    ChapterScrollBar(
                        total = count,
                        value = visiblePos,
                        label = visibleNumber,
                        onSeek = onSeek,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp, top = 6.dp, bottom = 10.dp)
                            .width(30.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterScrollBar(
    total: Int,
    value: Int,
    label: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val thumbHeight = 42.dp
        val fraction = (value.toFloat() / (total - 1)).coerceIn(0f, 1f)

        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(colors.outlineVariant)
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (maxHeight - thumbHeight) * fraction)
                .width(26.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$label",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimary,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(total) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun seekAt(y: Float) {
                            val idx = ((y / size.height) * (total - 1)).roundToInt()
                            onSeek(idx.coerceIn(0, total - 1))
                        }
                        seekAt(down.position.y)
                        drag(down.id) { change ->
                            seekAt(change.position.y)
                            change.consume()
                        }
                    }
                }
        )
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    current: Boolean,
    done: Boolean,
    playing: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
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
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.primary),
            )
        } else if (current) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 多选时的底部操作条：已选 N 章 + 全选 + 标为已读 / 未读 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    total: Int,
    onToggleAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (selectedCount > 0) "已选 $selectedCount / $total 章" else "勾选要操作的章节",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleAll) {
                    Text(if (selectedCount >= total) "取消全选" else "全选", fontSize = 12.sp)
                }
                TextButton(onClick = onClose) {
                    Text("完成", fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onMarkRead,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("标为已读", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onMarkUnread,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("标为未读", maxLines = 1)
                }
            }
        }
    }
}

/** 秒 → 小时，保留一位小数（331.2h 这种） */
private fun fmtHours(seconds: Double): String =
    String.format(Locale.US, "%.1f", seconds / 3600.0)

@Suppress("unused")
private fun chapterLabel(book: Book, record: Progress?): String {
    val index = (record?.index ?: 0).coerceIn(0, (book.chapterCount - 1).coerceAtLeast(0))
    return "第 ${index + 1} / ${book.chapterCount} 章"
}
