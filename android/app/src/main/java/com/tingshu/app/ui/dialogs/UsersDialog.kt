package com.tingshu.app.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingshu.app.ui.TingshuViewModel

/** 管理员：创建用户 / 重置密码 / 删除用户。 */
@Composable
fun UsersDialog(vm: TingshuViewModel, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val users by vm.users.collectAsStateWithLifecycle()
    val loading by vm.usersLoading.collectAsStateWithLifecycle()
    val error by vm.usersError.collectAsStateWithLifecycle()
    val me by vm.authUser.collectAsStateWithLifecycle()

    var newName by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("user") }
    var resetFor by remember { mutableStateOf<String?>(null) }
    var resetPass by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadUsers() }

    TingshuDialog(title = "用户管理", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "普通用户只能听书；管理员可以上传、编辑、删除书籍和创建用户。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )

            if (loading && users.isEmpty()) {
                Text("加载中…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            users.forEach { u ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(u.user, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (u.role == "admin") "管理员" else "普通用户",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (u.role == "admin") colors.primary else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        resetFor = u.user
                        resetPass = ""
                    }) { Text("重置密码", fontSize = 12.sp) }
                    if (u.user != me) {
                        TextButton(onClick = { vm.deleteUser(u.user) }) {
                            Text("删除", fontSize = 12.sp, color = colors.error)
                        }
                    }
                }
            }

            Text("创建用户", style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text("用户名（字母数字 _ -）") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            OutlinedTextField(
                value = newPass,
                onValueChange = { newPass = it },
                singleLine = true,
                label = { Text("密码（至少 6 位）") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("user" to "普通用户", "admin" to "管理员").forEach { (key, label) ->
                    FilterChip(
                        selected = newRole == key,
                        onClick = { newRole = key },
                        label = { Text(label) },
                    )
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = newName.isNotBlank() && newPass.length >= 6,
                    onClick = {
                        vm.createUser(newName.trim(), newPass, newRole)
                        newName = ""
                        newPass = ""
                    },
                ) { Text("创建") }
            }
        }
    }

    resetFor?.let { user ->
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text("重置「$user」的密码") },
            text = {
                Column {
                    Text("至少 6 位；重置后该用户需要重新登录。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = resetPass,
                        onValueChange = { resetPass = it },
                        singleLine = true,
                        label = { Text("新密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = resetPass.length >= 6,
                    onClick = {
                        vm.resetUserPassword(user, resetPass)
                        resetFor = null
                    },
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text("取消") } },
        )
    }
}
