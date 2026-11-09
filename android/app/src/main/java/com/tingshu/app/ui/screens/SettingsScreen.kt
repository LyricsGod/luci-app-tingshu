package com.tingshu.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.tingshu.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.data.ThemeMode
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.ServerAddressField
import com.tingshu.app.ui.dialogs.BackupDialog
import com.tingshu.app.ui.dialogs.PauseMarksDialog
import com.tingshu.app.ui.dialogs.UploadDialog
import com.tingshu.app.ui.dialogs.UsersDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: TingshuViewModel,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val error by vm.connectError.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.foundServers.collectAsStateWithLifecycle()
    val rescanning by vm.rescanning.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val libraryRoot by vm.libraryRoot.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val pauseMarks by vm.pauseMarks.collectAsStateWithLifecycle()
    val authUser by vm.authUser.collectAsStateWithLifecycle()
    val authRole by vm.authRole.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val miniAnimMs by vm.miniAnimMs.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val isAdmin = authRole == "admin"

    // 存完整地址（含协议/端口），分栏组件会自己拆开显示
    var address by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    var showBackup by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }

    // 进设置页时算一次缓存占用，清理后由 ViewModel 更新
    LaunchedEffect(Unit) { vm.refreshCacheSize() }
    var showUpload by remember { mutableStateOf(false) }
    var showUsers by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                    navigationIconContentColor = colors.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard("账号") {
                InfoLine("当前用户", authUser.ifBlank { "未登录" })
                InfoLine("角色", if (isAdmin) "管理员" else "普通用户")
                if (isAdmin) {
                    ActionRow("用户管理（创建用户 / 重置密码）") { showUsers = true }
                }
                ActionRow("退出登录") { vm.logout() }
            }

            SectionCard("服务器") {
                Text(
                    text = serverUrl.ifBlank { "未设置" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ServerAddressField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.connect(address) },
                        enabled = !connecting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (connecting) "连接中…" else "保存并连接") }
                    OutlinedButton(
                        onClick = { vm.scanLan() },
                        enabled = !scanning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (scanning) "扫描中…" else "扫描局域网") }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                found.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.connect(server) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = server.removePrefix("http://").trimEnd('/'),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text("使用", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }

            SectionCard("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = theme == mode,
                            onClick = { vm.setTheme(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.AUTO -> "跟随系统"
                                        ThemeMode.DARK -> "夜间"
                                        ThemeMode.LIGHT -> "日间"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.primary,
                            ),
                        )
                    }
                }
            }

            SectionCard("首页布局") {
                Text(
                    "每行显示几本（每个账号独立保存）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4, 5).forEach { n ->
                        FilterChip(
                            selected = columns == n,
                            onClick = { vm.setColumns(n) },
                            label = { Text("$n 列") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.primary,
                            ),
                        )
                    }
                }
            }

            SectionCard("迷你播放器") {
                Text(
                    "首次出现时从下往上滑入的时长（默认 0.5 秒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        0 to "关闭", 300 to "0.3s", 500 to "0.5s",
                        1000 to "1s", 1500 to "1.5s", 2000 to "2s",
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = miniAnimMs == ms,
                            onClick = { vm.setMiniAnimMs(ms) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.primary,
                            ),
                        )
                    }
                }
            }

            SectionCard("书库") {
                InfoLine("书籍", "${books.size} 本")
                InfoLine("章节", "${books.sumOf { it.chapterCount }} 章")
                if (libraryRoot.isNotBlank()) InfoLine("路由器目录", libraryRoot)
                if (isAdmin) {
                    ActionRow("上传书籍（文件夹 / 压缩包）") { showUpload = true }
                    ActionRow("重新扫描路由器书库", busy = rescanning) { vm.rescanLibrary() }
                }
                ActionRow("刷新书架", busy = refreshing) { vm.reload() }
            }

            SectionCard("数据") {
                InfoLine("听书记录", "${progress.values.count { it.isReal }} 本")
                InfoLine("暂停记录", "${pauseMarks.size} 条")
                InfoLine("本地缓存", formatBytes(cacheSize))
                if (isAdmin) ActionRow("服务器备份 / 恢复") { showBackup = true }
                ActionRow("暂停记录") { showMarks = true }
                ActionRow("清理缓存") { vm.clearCache() }
            }

            SectionCard("关于") {
                InfoLine("客户端版本", BuildConfig.VERSION_NAME)
                InfoLine("接口", "/cgi-ts · 听书 1.6.x")
                ActionRow("在浏览器中打开网页版") {
                    if (serverUrl.isNotBlank()) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl)))
                        }.onFailure {
                            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            TextButton(
                onClick = { vm.logout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录", color = colors.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showBackup) {
        BackupDialog(vm = vm, onDismiss = { showBackup = false })
    }
    if (showMarks) {
        PauseMarksDialog(
            vm = vm,
            bookId = null,
            onJump = { showMarks = false },
            onDismiss = { showMarks = false },
        )
    }
    if (showUpload) {
        UploadDialog(vm = vm, onDismiss = { showUpload = false })
    }
    if (showUsers) {
        UsersDialog(vm = vm, onDismiss = { showUsers = false })
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun InfoLine(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(text: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
