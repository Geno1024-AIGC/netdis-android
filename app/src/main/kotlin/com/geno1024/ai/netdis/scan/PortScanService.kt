package com.geno1024.ai.netdis.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.geno1024.ai.netdis.MainActivity
import com.geno1024.ai.netdis.R
import com.geno1024.ai.netdis.model.PortScanState
import com.geno1024.ai.netdis.net.PortScanBus
import com.geno1024.ai.netdis.net.TcpProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the full TCP scan outside the activity. The progress lives in
 * [PortScanBus], so the in-app card and this notification always show the same
 * numbers. The notification is dismissible after the scan finishes.
 */
class PortScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var current: PortScanState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelScan()
            ACTION_START -> startScan(intent)
        }
        return START_NOT_STICKY
    }

    private fun startScan(intent: Intent) {
        if (job?.isActive == true) return
        val ip = intent.getStringExtra(EXTRA_IP)?.trim().orEmpty()
        if (ip.isBlank()) {
            stopSelf()
            return
        }
        val id = intent.getLongExtra(EXTRA_ID, System.currentTimeMillis())
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val initial = PortScanState(
            id = id,
            ip = ip,
            label = label,
            running = true,
            total = 65535,
        )
        current = initial
        PortScanBus.publish(initial)
        startForegroundCompat(initial, finished = false)

        job = scope.launch {
            try {
                val open = TcpProbe.scanAllPorts(ip) { scanned, ports ->
                    val next = (current ?: initial).copy(
                        scanned = scanned,
                        openPorts = ports,
                        total = 65535,
                    )
                    current = next
                    PortScanBus.publish(next)
                    notifyProgress(next, finished = false)
                }
                finish(
                    (current ?: initial).copy(
                        running = false,
                        finished = true,
                        scanned = 65535,
                        openPorts = open,
                        message = "扫描完成",
                    ),
                )
            } catch (e: CancellationException) {
                finish(
                    (current ?: initial).copy(
                        running = false,
                        finished = true,
                        cancelled = true,
                        message = "已取消",
                    ),
                )
                throw e
            } catch (e: Exception) {
                finish(
                    (current ?: initial).copy(
                        running = false,
                        finished = true,
                        message = e.message?.let { "端口扫描失败：$it" } ?: "端口扫描失败",
                    ),
                )
            }
        }
    }

    private fun cancelScan() {
        if (job?.isActive != true) return
        job?.cancel()
    }

    private fun finish(state: PortScanState) {
        current = state
        PortScanBus.publish(state)
        notifyProgress(state, finished = true)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startForegroundCompat(state: PortScanState, finished: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(state, finished),
            type,
        )
    }

    private fun notifyProgress(state: PortScanState, finished: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(state, finished))
    }

    private fun notification(state: PortScanState, finished: Boolean): Notification {
        val name = state.label.ifBlank { state.ip }
        val title = when {
            !finished -> "正在扫描端口 · $name"
            state.cancelled -> "端口扫描已取消 · $name"
            state.message?.contains("失败") == true -> "端口扫描失败 · $name"
            else -> "端口扫描完成 · $name"
        }
        val text = when {
            !finished -> "${state.scanned}/${state.total} · 已发现 ${state.openPorts.size} 个开放端口"
            state.cancelled -> "已停止，发现 ${state.openPorts.size} 个开放端口"
            state.openPorts.isEmpty() -> "1–65535 全部扫描完毕，没有发现开放端口"
            else -> "发现 ${state.openPorts.size} 个开放端口：${state.openPorts.take(12).joinToString(", ")}" +
                if (state.openPorts.size > 12) " …" else ""
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (finished) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(state.total, state.scanned, false)
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelIntent(),
            )
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, PortScanService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "端口扫描",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示全端口扫描的进度和结果"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.geno1024.ai.netdis.action.PORT_SCAN_START"
        const val ACTION_CANCEL = "com.geno1024.ai.netdis.action.PORT_SCAN_CANCEL"
        const val EXTRA_ID = "port_scan_id"
        const val EXTRA_IP = "port_scan_ip"
        const val EXTRA_LABEL = "port_scan_label"
        private const val CHANNEL_ID = "port_scan"
        private const val NOTIFICATION_ID = 4102
    }
}
