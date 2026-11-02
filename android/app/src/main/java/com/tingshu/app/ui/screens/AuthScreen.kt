package com.tingshu.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.ui.TingshuViewModel
import com.tingshu.app.ui.components.LogoMark

/**
 * 登录页。服务器第一次使用（还没有用户）时，点「连接并登录」会创建管理员。
 * 之后用管理员或管理员创建的普通账号登录。
 */
@Composable
fun AuthScreen(vm: TingshuViewModel) {
    val colors = MaterialTheme.colorScheme
    val busy by vm.authBusy.collectAsStateWithLifecycle()
    val error by vm.authError.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val found by vm.foundServers.collectAsStateWithLifecycle()

    var address by rememberSaveable {
        mutableStateOf(vm.serverUrl.value.ifBlank { "192.168.2.1:8686" })
    }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (!busy) vm.authenticate(address, user.trim(), pass)
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 26.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LogoMark(76.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "听书",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp),
                color = colors.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "登录后，每个账号有自己的听书记录和首页布局",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务器", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        placeholder = { Text("路由器IP:8686") },
                        leadingIcon = { Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (!scanning) vm.scanLan() },
                            enabled = !scanning,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(7.dp))
                                Text("扫描中…")
                            } else {
                                Text("扫描局域网")
                            }
                        }
                    }
                    if (found.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            found.take(6).forEach { server ->
                                Text(
                                    text = server,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { address = server }
                                        .padding(vertical = 5.dp, horizontal = 2.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text("账号", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        singleLine = true,
                        placeholder = { Text("用户名") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        singleLine = true,
                        placeholder = { Text("密码（至少 6 位）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )

                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
                    }

                    Button(
                        onClick = { submit() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary,
                            )
                        } else {
                            Text("连接并登录")
                        }
                    }

                    Text(
                        text = "第一次使用：服务器还没有账号，会直接用上面的用户名和密码创建管理员。\n" +
                            "已经初始化：用管理员或管理员创建的账号登录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "普通用户只能听书；管理员才能上传、编辑、删除书籍和创建用户。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
