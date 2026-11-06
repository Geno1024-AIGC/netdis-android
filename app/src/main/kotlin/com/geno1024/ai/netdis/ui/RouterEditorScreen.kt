package com.geno1024.ai.netdis.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.net.ArpTable
import com.geno1024.ai.netdis.router.RouterAdapters

/**
 * The add/edit page. It is deliberately a full page instead of a dialog: a
 * router has enough fields (address, protocol, type, MAC, credentials, note)
 * that a dialog becomes cramped on a phone. [initial] with a blank id means a
 * new router.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterEditorScreen(
    initial: RouterConfig,
    onSave: (RouterConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var host by remember(initial.id) { mutableStateOf(initial.host) }
    var selectedScheme by remember(initial.id) { mutableStateOf(initial.scheme.ifBlank { "http" }) }
    var portText by remember(initial.id) {
        mutableStateOf(if (initial.port > 0) initial.port.toString() else "")
    }
    var type by remember(initial.id) { mutableStateOf(initial.type) }
    var mac by remember(initial.id) { mutableStateOf(initial.mac.orEmpty()) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    var passwordVisible by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    val port = portText.toIntOrNull()
    val portValid = portText.isBlank() || (port != null && port in 1..65535)
    val normalizedMac = ArpTable.normalizeMac(mac)
    val macValid = mac.isBlank() || normalizedMac != null
    val canSave = host.trim().isNotEmpty() && portValid && macValid
    val isNew = initial.id.isBlank()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回路由器列表",
                )
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(
                    if (isNew) "添加路由器" else "编辑路由器",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "登录后可以读取 DHCP 客户端、无线关联和 WAN 上联",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("名称（可选）") },
            placeholder = { Text("客厅主路由") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("地址") },
            placeholder = { Text("192.168.1.1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("管理协议", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in listOf("http", "https")) {
                FilterChip(
                    selected = selectedScheme == option,
                    onClick = { selectedScheme = option },
                    label = { Text(option.uppercase()) },
                    shape = CircleShape,
                )
            }
        }
        OutlinedTextField(
            value = portText,
            onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
            label = { Text("端口（留空自动）") },
            placeholder = { Text("80 / 443 / 8080") },
            singleLine = true,
            isError = !portValid,
            supportingText = if (!portValid) {
                { Text("端口需在 1–65535 之间") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("识别方式", style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = type.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("路由器类型") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                for (option in RouterAdapters.selectableTypes) {
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            type = option
                            typeExpanded = false
                        },
                    )
                }
            }
        }
        Text(
            "「自动识别」会依次尝试所有已注册的适配器；已知型号时选定品牌可以少走弯路。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it.take(32) },
            label = { Text("MAC 地址（可选）") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
            isError = !macValid,
            supportingText = {
                Text(
                    if (!macValid) {
                        "MAC 格式不正确"
                    } else {
                        "用 MAC 绑定登录凭据，路由器更换地址后仍能自动匹配"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            placeholder = { Text("root / admin / useradmin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("备注（可选）") },
            placeholder = { Text("例如：弱电箱里的二级路由") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        Button(
            enabled = canSave,
            onClick = {
                onSave(
                    initial.copy(
                        label = label.trim(),
                        host = host.trim(),
                        scheme = selectedScheme,
                        port = port ?: 0,
                        type = type,
                        mac = normalizedMac,
                        username = username.trim(),
                        password = password,
                        note = note.trim(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存")
        }
    }
}
