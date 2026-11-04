package com.tingshu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tingshu.app.data.Book
import com.tingshu.app.data.Collection
import com.tingshu.app.data.Urls
import com.tingshu.app.playback.PlayerUiState

/** 和网页端 app.js 里的 PALETTES 一样，没封面的书按标题哈希挑一组。 */
private val PALETTES = listOf(
    Color(0xFFCAA27C) to Color(0xFF8A6849),
    Color(0xFFA8B6C6) to Color(0xFF5E6F81),
    Color(0xFFC4B1A1) to Color(0xFF7D6A58),
    Color(0xFFB7C1A6) to Color(0xFF5E6B51),
    Color(0xFFC9AFB8) to Color(0xFF7D5F6B),
    Color(0xFFAFBCCB) to Color(0xFF596A7B),
    Color(0xFFCCBD9D) to Color(0xFF8A7A55),
    Color(0xFFC1AAA0) to Color(0xFF7A5A52),
    Color(0xFFB3B8C9) to Color(0xFF5C6178),
    Color(0xFFCBB49A) to Color(0xFF86694F),
)

fun coverBrush(book: Book?): Brush {
    if (book == null) return paletteBrush(null)
    val index = book.palette?.takeIf { it in PALETTES.indices } ?: paletteIndex(book.folderTitle)
    return paletteBrush(index)
}

fun paletteBrush(index: Int?): Brush {
    val pair = PALETTES[index?.takeIf { it in PALETTES.indices } ?: 0]
    return Brush.linearGradient(listOf(pair.first, pair.second), start = Offset.Zero, end = Offset.Infinite)
}

private fun paletteIndex(title: String): Int {
    // 对齐 JS：h = (h * 131 + code) >>> 0，再对调色板取模
    var h = 7
    for (ch in title) {
        h = h * 131 + ch.code
    }
    val unsigned = h.toLong() and 0xFFFFFFFFL
    return (unsigned % PALETTES.size).toInt()
}

@Composable
fun BookCover(
    book: Book?,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    showTitle: Boolean = true,
    compactTitle: Boolean = false,
    titleStyle: TextStyle? = null,
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape)) {
        Box(Modifier.fillMaxSize().background(coverBrush(book)))
        if (book != null) {
            val model: Any? = book.coverFile ?: book.coverUrl
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showTitle) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(if (compactTitle) 4.dp else 14.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = book.title,
                        style = titleStyle ?: if (compactTitle) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        color = Color(0xFFFFF8F0),
                        maxLines = if (compactTitle) 2 else 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun LogoMark(size: Dp = 42.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Color(0xFFD18A5A), Color(0xFF9C4F2C)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Color(0xFFFFF8F0),
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

@Composable
fun CollectionCover(
    collection: Collection,
    booksById: Map<String, Book>,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
) {
    val list = collection.bookIds.mapNotNull { booksById[it] }.take(9)
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(corner)).background(colors.surfaceVariant)) {
        if (list.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFFCBB49A), Color(0xFF86694F))))
            )
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = Color(0xFFFFF8F0),
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(3) { row ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            val book = list.getOrNull(index)
                            Box(Modifier.weight(1f).fillMaxSize()) {
                                if (book != null) {
                                    BookCover(
                                        book = book,
                                        modifier = Modifier.fillMaxSize(),
                                        corner = 3.dp,
                                        showTitle = true,
                                        compactTitle = true,
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(colors.surface.copy(alpha = 0.35f))
                                            .border(
                                                width = 1.dp,
                                                color = colors.outlineVariant.copy(alpha = 0.45f),
                                                shape = RoundedCornerShape(3.dp),
                                            )
                                    )
                                }
                                if (index == 8 && collection.bookIds.size > 9) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color(0x66000000)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "+${collection.bookIds.size - 8}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    book: Book?,
    state: PlayerUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.primary,
            trackColor = colors.outlineVariant,
            drawStopIndicator = {},
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, Modifier.size(42.dp), corner = 10.dp, showTitle = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book?.title ?: state.bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.trackTitle.ifBlank { "继续听" },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSpeed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatSpeed(state.speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
            IconButton(onClick = onTimer, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = "定时",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(42.dp)) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = colors.primary,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(42.dp), enabled = state.hasNext) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一章",
                    tint = if (state.hasNext) colors.onSurface else colors.outline,
                )
            }
        }
    }
}

fun formatTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0L) else 0L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTimeMs(millis: Long): String = formatTime(millis / 1000.0)

fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) {
        String.format(java.util.Locale.US, "%.1f", speed)
    } else {
        String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "$text×"
}

/**
 * 服务器地址输入：协议用 chip 选，主机和端口分两栏填。
 * 手机小键盘上敲 `://` 和 `:` 很容易漏，端口单独一栏也方便远程改成 443 等端口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val parts = Urls.splitServer(value)

    fun emit(scheme: String = parts.scheme, host: String = parts.host, port: String = parts.port) {
        // 兼容把完整地址直接粘进主机栏：自动拆出协议和端口。
        // 只有主机栏里真的写了端口才覆盖现有端口，否则用户填的 8888 会被默认值顶掉。
        val pasted = Urls.splitServer(host)
        val pastedFull = host.contains("://")
        val authority = host.substringAfter("://").substringBefore('/')
        val typedPort = authority.substringAfterLast(':', "")
        val hasTypedPort = typedPort.isNotEmpty() && typedPort.all { it.isDigit() }
        onValueChange(
            Urls.joinServer(
                scheme = if (pastedFull) pasted.scheme else scheme,
                host = pasted.host,
                port = when {
                    hasTypedPort -> pasted.port
                    pastedFull -> Urls.DEFAULT_PORT
                    else -> port
                },
                path = if (pastedFull) pasted.path else parts.path,
            )
        )
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "协议",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            listOf("http", "https").forEach { scheme ->
                FilterChip(
                    selected = parts.scheme == scheme,
                    onClick = { emit(scheme = scheme) },
                    label = { Text("$scheme://") },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = parts.host,
                onValueChange = { emit(host = it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("主机 / 域名") },
                placeholder = {
                    Text(
                        text = "192.168.2.1 或域名",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
            OutlinedTextField(
                value = parts.port,
                onValueChange = {
                    emit(port = it.filter { ch -> ch.isDigit() }.ifEmpty { Urls.DEFAULT_PORT })
                },
                modifier = Modifier.width(104.dp),
                singleLine = true,
                label = { Text("端口") },
                placeholder = { Text("8686", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = keyboardOptions.imeAction,
                ),
                keyboardActions = keyboardActions,
            )
        }
    }
}
