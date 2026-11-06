package com.geno1024.netdis.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.geno1024.netdis.model.DeviceKind
import com.geno1024.netdis.model.HostSource
import com.geno1024.netdis.model.LinkKind
import com.geno1024.netdis.model.NodeKind
import com.geno1024.netdis.ui.theme.Mint
import com.geno1024.netdis.ui.theme.SignalAmber
import com.geno1024.netdis.ui.theme.SignalBlue
import com.geno1024.netdis.ui.theme.SignalLeaf
import com.geno1024.netdis.ui.theme.SignalRose
import com.geno1024.netdis.ui.theme.SignalViolet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun DeviceKind.label(): String = when (this) {
    DeviceKind.ROUTER -> "路由器"
    DeviceKind.PHONE -> "手机 / 平板"
    DeviceKind.COMPUTER -> "电脑"
    DeviceKind.TV -> "电视 / 播放器"
    DeviceKind.SPEAKER -> "音箱"
    DeviceKind.PRINTER -> "打印机"
    DeviceKind.CAMERA -> "摄像头"
    DeviceKind.NAS -> "NAS / 存储"
    DeviceKind.IOT -> "智能家居"
    DeviceKind.CONSOLE -> "游戏机"
    DeviceKind.UNKNOWN -> "未知设备"
}

fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.ROUTER -> Icons.Outlined.Router
    DeviceKind.PHONE -> Icons.Outlined.Smartphone
    DeviceKind.COMPUTER -> Icons.Outlined.Computer
    DeviceKind.TV -> Icons.Outlined.Tv
    DeviceKind.SPEAKER -> Icons.Outlined.Speaker
    DeviceKind.PRINTER -> Icons.Outlined.Print
    DeviceKind.CAMERA -> Icons.Outlined.CameraAlt
    DeviceKind.NAS -> Icons.Outlined.Storage
    DeviceKind.IOT -> Icons.Outlined.Sensors
    DeviceKind.CONSOLE -> Icons.Outlined.SportsEsports
    DeviceKind.UNKNOWN -> Icons.Outlined.DevicesOther
}

fun NodeKind.icon(): ImageVector = when (this) {
    NodeKind.INTERNET -> Icons.Outlined.Public
    NodeKind.ROUTER -> Icons.Outlined.Router
    NodeKind.DEVICE -> Icons.Outlined.DevicesOther
}

fun NodeKind.tint(): Color = when (this) {
    NodeKind.INTERNET -> SignalBlue
    NodeKind.ROUTER -> Mint
    NodeKind.DEVICE -> SignalViolet
}

fun DeviceKind.tint(): Color = when (this) {
    DeviceKind.ROUTER -> Mint
    DeviceKind.PHONE -> SignalBlue
    DeviceKind.COMPUTER -> SignalViolet
    DeviceKind.TV -> SignalAmber
    DeviceKind.SPEAKER -> SignalLeaf
    DeviceKind.PRINTER -> SignalRose
    DeviceKind.CAMERA -> SignalAmber
    DeviceKind.NAS -> SignalBlue
    DeviceKind.IOT -> SignalLeaf
    DeviceKind.CONSOLE -> SignalViolet
    DeviceKind.UNKNOWN -> Color(0xFF9AA9AD)
}

fun LinkKind.label(): String = when (this) {
    LinkKind.ETHERNET -> "有线"
    LinkKind.WIFI -> "Wi-Fi"
    LinkKind.UNKNOWN -> "未知连接"
}

fun LinkKind.icon(): ImageVector = when (this) {
    LinkKind.ETHERNET -> Icons.Outlined.Lan
    LinkKind.WIFI -> Icons.Outlined.Wifi
    LinkKind.UNKNOWN -> Icons.Outlined.AccountTree
}

fun HostSource.label(): String = when (this) {
    HostSource.ARP -> "ARP"
    HostSource.TCP -> "端口"
    HostSource.ICMP -> "Ping"
    HostSource.MDNS -> "mDNS"
    HostSource.SSDP -> "SSDP"
    HostSource.NETBIOS -> "NetBIOS"
    HostSource.DNS -> "DNS"
    HostSource.DHCP -> "DHCP"
    HostSource.WIFI -> "Wi-Fi"
    HostSource.ROUTER_ARP -> "路由表"
    HostSource.MANUAL -> "本机"
}

fun formatAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "从未"
    val delta = (now - timestamp).coerceAtLeast(0)
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> "${delta / 60_000} 分钟前"
        delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

fun formatDuration(millis: Long): String = when {
    millis < 1000 -> "${millis}ms"
    millis < 60_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 60_000}m${(millis % 60_000) / 1000}s"
}
