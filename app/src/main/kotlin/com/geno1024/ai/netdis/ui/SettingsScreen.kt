package com.geno1024.ai.netdis.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.geno1024.ai.netdis.model.AppSettings
import com.geno1024.ai.netdis.model.ThemeMode
import com.geno1024.ai.netdis.ui.theme.Mint
import com.geno1024.ai.netdis.update.ApkInstaller
import com.geno1024.ai.netdis.update.CanaryManifest
import com.geno1024.ai.netdis.update.Mirrors
import java.util.Locale
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/${CanaryManifest.REPO}"

/**
 * Everything that changes scan behaviour lives on one page, plus the update
 * channel. The update card deliberately shows the exact version and digest so
 * a canary build can be compared with the release page without guessing.
 */
@Composable
fun SettingsScreen(
    state: AppUiState,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onUpdateMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val update = state.update
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // The user often leaves for the permission page and comes straight back, so
    // re-read the grant on every resume instead of trusting the first check.
    var canInstall by remember { mutableStateOf(ApkInstaller.canRequestInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = ApkInstaller.canRequestInstall(context)
        onPauseOrDispose { }
    }

    fun install(file: java.io.File) {
        if (!ApkInstaller.canRequestInstall(context)) {
            onUpdateMessage(
                ApkInstaller.launchSettings(context) ?: "请允许本应用安装未知来源应用，然后返回再点一次安装",
            )
            return
        }
        onUpdateMessage(
            ApkInstaller.launchInstall(context, file) ?: "已打开系统安装器，请在弹窗中确认安装",
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "调整扫描强度与更新渠道",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        GlassCard(padding = 16.dp) {
            SectionTitle("外观")
            Spacer(Modifier.size(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.DARK to "深色",
                    ThemeMode.LIGHT to "浅色",
                )
                for ((mode, label) in options) {
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { onSettingsChange { it.copy(theme = mode) } },
                        label = { Text(label) },
                        shape = CircleShape,
                    )
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        GlassCard(padding = 16.dp) {
            SectionTitle("扫描")
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = settings.subnetOverride,
                onValueChange = { value ->
                    onSettingsChange { it.copy(subnetOverride = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手动网段（可选）") },
                placeholder = { Text("192.168.1.0/24") },
                supportingText = { Text("留空时自动读取当前 Wi-Fi 或网线接口") },
                singleLine = true,
            )
            Spacer(Modifier.size(6.dp))
            SwitchRow(
                title = "深度扫描",
                description = "探测更多端口，结果更准，耗时略长",
                checked = settings.deepScan,
                onCheckedChange = { value -> onSettingsChange { it.copy(deepScan = value) } },
            )
            SwitchRow(
                title = "显示离线设备",
                description = "在拓扑与设备列表里保留上次见到的设备",
                checked = settings.showOffline,
                onCheckedChange = { value -> onSettingsChange { it.copy(showOffline = value) } },
            )
            SwitchRow(
                title = "Root 读取 ARP 表",
                description = "Android 10+ 默认禁止读取本机 ARP 表；已 root 并授权后可用 su 读取本网段 MAC",
                checked = settings.readArpWithRoot,
                onCheckedChange = { value -> onSettingsChange { it.copy(readArpWithRoot = value) } },
            )
            Spacer(Modifier.size(10.dp))
            TimeoutSlider(
                value = settings.probeTimeoutMs,
                onChange = { value -> onSettingsChange { it.copy(probeTimeoutMs = value) } },
            )
        }

        Spacer(Modifier.size(12.dp))

        GlassCard(padding = 16.dp) {
            SectionTitle("更新") {
                Text(
                    "当前 ${state.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onCheckUpdate,
                    enabled = !update.checking && !update.downloading,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (update.checking) "检查中…" else "检查更新")
                }
                if (update.info != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "可更新到 ${update.info.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Mint,
                    )
                }
            }

            update.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            update.info?.let { info ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        InfoRow("版本", info.versionName)
                        InfoRow("构建号", info.runNumber.toString())
                        InfoRow("大小", formatBytes(info.sizeBytes))
                        InfoRow("SHA-256", info.shortSha)
                    }
                }
            }

            if (update.downloading) {
                Spacer(Modifier.size(10.dp))
                if (update.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { update.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(update.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val file = update.file
                if (file != null) {
                    Button(onClick = { install(file) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (canInstall) "安装更新" else "允许安装后更新")
                    }
                } else {
                    Button(
                        onClick = onDownloadUpdate,
                        enabled = update.info != null && !update.downloading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (update.downloading) "下载中…" else "下载更新")
                    }
                }
            }

            if (update.file != null && !canInstall) {
                Text(
                    "系统默认禁止安装未知来源应用。点击安装按钮会先跳到授权页，允许本应用安装后返回即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.size(14.dp))
            Text("下载镜像", style = MaterialTheme.typography.labelMedium)
            Text(
                "直连 GitHub 最可靠；网络不通时再尝试第三方代理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mirror in Mirrors.PRESETS) {
                    FilterChip(
                        selected = settings.updateMirrorPrefix == mirror.prefix,
                        onClick = {
                            onSettingsChange { it.copy(updateMirrorPrefix = mirror.prefix) }
                        },
                        label = { Text(mirror.label) },
                        shape = CircleShape,
                    )
                }
            }
            OutlinedTextField(
                value = settings.updateMirrorPrefix,
                onValueChange = { value -> onSettingsChange { it.copy(updateMirrorPrefix = value) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("自定义 GitHub 加速前缀") },
                placeholder = { Text("https://ghfast.top") },
                supportingText = { Text("留空等同于直连 GitHub") },
                singleLine = true,
            )
            TextButton(onClick = { uriHandler.openUri("$REPO_URL/releases/tag/canary") }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("在浏览器打开 canary 发布页")
            }
        }

        Spacer(Modifier.size(12.dp))

        GlassCard(padding = 16.dp) {
            SectionTitle("关于")
            Spacer(Modifier.size(8.dp))
            InfoRow("版本", state.versionName)
            InfoRow("应用", "网络拓扑（NetDis）")
            InfoRow("说明", "扫描局域网、登录多级路由、绘制拓扑图")
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "路由器密码由 Android Keystore 生成的密钥加密后保存在本机。扫描只在当前局域网内进行，不会把凭据或设备清单发往服务器。安装更新时始终校验 SHA-256，并通过 Android 的应用签名校验阻止他人替换安装包。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("项目主页")
            }
        }

        Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TimeoutSlider(value: Int, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "探测超时",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${local.roundToInt()} ms",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onChange(local.roundToInt().coerceIn(120, 1500)) },
        valueRange = 120f..1500f,
        steps = 22,
    )
    Text(
        "地址越多或 Wi-Fi 越慢，可以适当调大；调小能缩短扫描时间。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
