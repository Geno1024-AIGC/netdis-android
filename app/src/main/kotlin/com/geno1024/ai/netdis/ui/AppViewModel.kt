package com.geno1024.ai.netdis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.netdis.BuildConfig
import com.geno1024.ai.netdis.model.AppSettings
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.ScanProgress
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.model.ScanStage
import com.geno1024.ai.netdis.net.DeviceScanner
import com.geno1024.ai.netdis.router.RouterParsing
import com.geno1024.ai.netdis.router.RouterScanner
import com.geno1024.ai.netdis.router.TrafficLog
import com.geno1024.ai.netdis.storage.CredentialVault
import com.geno1024.ai.netdis.storage.DeviceNotesStore
import com.geno1024.ai.netdis.storage.RouterRepository
import com.geno1024.ai.netdis.storage.SettingsStore
import com.geno1024.ai.netdis.storage.SnapshotStore
import com.geno1024.ai.netdis.topo.TopologyBuilder
import com.geno1024.ai.netdis.update.CanaryInfo
import com.geno1024.ai.netdis.update.CanaryManifest
import com.geno1024.ai.netdis.update.DownloadResult
import com.geno1024.ai.netdis.update.Mirror
import com.geno1024.ai.netdis.update.Mirrors
import com.geno1024.ai.netdis.update.UpdateResult
import com.geno1024.ai.netdis.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class UpdateUiState(
    val checking: Boolean = false,
    val info: CanaryInfo? = null,
    val downloading: Boolean = false,
    val progress: Float = -1f,
    val file: File? = null,
    val message: String? = null,
)

/** Per-router result of an explicit "log in and refresh" tap. */
data class RouterLoginUiState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val clientCount: Int = 0,
    val finishedAt: Long = 0,
)

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val routers: List<RouterConfig> = emptyList(),
    val snapshot: ScanSnapshot? = null,
    val progress: ScanProgress = ScanProgress(),
    val scanning: Boolean = false,
    val error: String? = null,
    val update: UpdateUiState = UpdateUiState(),
    /** `null` until the vault has picked a key source on a worker thread. */
    val credentialMode: CredentialVault.Mode? = null,
    val routerLogins: Map<String, RouterLoginUiState> = emptyMap(),
    val trafficCount: Int = 0,
) {
    val versionName: String get() = BuildConfig.VERSION_NAME
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val credentialVault = CredentialVault(application)
    private val routerRepository = RouterRepository(application, credentialVault)
    private val snapshotStore = SnapshotStore(application)
    private val deviceNotes = DeviceNotesStore(application)
    private val scanner = DeviceScanner(application)

    private val initialSettings = settingsStore.load()
    private val initialRouters = routerRepository.load()

    private val _state = MutableStateFlow(
        AppUiState(
            settings = initialSettings,
            routers = initialRouters,
            snapshot = snapshotStore.load()?.let { annotate(it, initialRouters) },
            trafficCount = TrafficLog.count(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Capture is opt-in and lives in a process-wide buffer because the HTTP
        // client is a singleton shared by every adapter.
        TrafficLog.enabled = initialSettings.captureTraffic
        // Key generation can hit the TEE and fail, so never do it on the main
        // thread. The result also tells the UI whether passwords are protected
        // by the hardware keystore or by the software fallback.
        viewModelScope.launch(Dispatchers.IO) {
            val mode = credentialVault.mode()
            _state.update { it.copy(credentialMode = mode) }
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val current = _state.value
            _state.update {
                it.copy(
                    scanning = true,
                    error = null,
                    progress = ScanProgress(ScanStage.PREPARE, 0.01f, "准备扫描"),
                )
            }
            try {
                val scanned = scanner.scan(current.settings, current.routers) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                val snapshot = annotate(scanned, current.routers)
                snapshotStore.save(snapshot)
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        scanning = false,
                        trafficCount = TrafficLog.count(),
                        progress = ScanProgress(
                            stage = ScanStage.DONE,
                            fraction = 1f,
                            detail = "发现 ${snapshot.hosts.size} 台设备",
                            found = snapshot.hosts.size,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        scanning = false,
                        progress = ScanProgress(ScanStage.IDLE),
                        error = "扫描已取消",
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        scanning = false,
                        progress = ScanProgress(ScanStage.FAILED, 1f),
                        error = e.message ?: "扫描失败",
                    )
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Fits the device list and warnings of the last scan, keeping the newest
     * known picture while a new scan is in flight.
     */
    fun saveRouter(config: RouterConfig) {
        val normalized = if (config.id.isBlank()) config.copy(id = UUID.randomUUID().toString()) else config
        val existing = _state.value.routers
        val updated = if (existing.any { it.id == normalized.id }) {
            existing.map { if (it.id == normalized.id) normalized else it }
        } else {
            existing + normalized
        }
        routerRepository.save(updated)
        _state.update { it.copy(routers = updated) }
    }

    fun deleteRouter(id: String) {
        val updated = _state.value.routers.filterNot { it.id == id }
        routerRepository.save(updated)
        _state.update { it.copy(routers = updated) }
    }

    fun setRouterEnabled(id: String, enabled: Boolean) {
        val updated = _state.value.routers.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        routerRepository.save(updated)
        _state.update { it.copy(routers = updated) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_state.value.settings)
        settingsStore.save(updated)
        TrafficLog.enabled = updated.captureTraffic
        _state.update { it.copy(settings = updated, trafficCount = TrafficLog.count()) }
    }

    /**
     * Logs into one saved router immediately instead of waiting for a full LAN
     * scan, replaces its snapshot and rebuilds the graph in place. A failure
     * keeps the previous client picture and surfaces the reason on the card.
     */
    fun loginRouter(configId: String) {
        val config = _state.value.routers.firstOrNull { it.id == configId } ?: return
        if (_state.value.routerLogins[configId]?.running == true) return
        setRouterLogin(configId, RouterLoginUiState(running = true, message = "正在登录…"))
        viewModelScope.launch {
            val timeout = (_state.value.settings.probeTimeoutMs * 8 + 1200).coerceAtLeast(3500)
            val result = try {
                RouterScanner.scan(config, timeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setRouterLogin(
                    configId,
                    RouterLoginUiState(success = false, message = e.message ?: "登录失败"),
                )
                return@launch
            }

            if (result.reachable) {
                val now = System.currentTimeMillis()
                val current = _state.value.snapshot
                val base = current ?: ScanSnapshot(
                    startedAt = now,
                    finishedAt = now,
                    local = null,
                )
                val routers = base.routers.filterNot { it.sameRouterAs(config) } + result
                // Fold the freshly fetched clients into the device list too, so
                // the Devices tab and the graph agree without a full rescan.
                val hosts = RouterParsing.mergeHosts(base.hosts + result.clients)
                val rebuilt = annotate(
                    base.copy(routers = routers, hosts = hosts, finishedAt = now),
                    _state.value.routers,
                )
                snapshotStore.save(rebuilt)
                _state.update {
                    it.copy(
                        snapshot = rebuilt,
                        trafficCount = TrafficLog.count(),
                        routerLogins = it.routerLogins + (
                            configId to RouterLoginUiState(
                                success = true,
                                message = result.message ?: "已登录",
                                clientCount = result.clientCount,
                                finishedAt = now,
                            )
                            ),
                    )
                }
            } else {
                setRouterLogin(
                    configId,
                    RouterLoginUiState(
                        success = false,
                        message = result.message ?: "登录失败",
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
                _state.update {
                    it.copy(error = result.message ?: "登录失败", trafficCount = TrafficLog.count())
                }
            }
        }
    }

    fun saveDeviceNote(host: HostRecord, alias: String, note: String) {
        deviceNotes.save(host, alias, note)
        refreshNotes()
    }

    fun deleteDeviceNote(host: HostRecord) {
        deviceNotes.remove(host)
        refreshNotes()
    }

    fun clearTraffic() {
        TrafficLog.clear()
        _state.update { it.copy(trafficCount = 0) }
    }

    private fun refreshNotes() {
        val current = _state.value.snapshot ?: return
        val updated = annotate(current, _state.value.routers)
        snapshotStore.save(updated)
        _state.update { it.copy(snapshot = updated) }
    }

    private fun setRouterLogin(configId: String, state: RouterLoginUiState) {
        _state.update { it.copy(routerLogins = it.routerLogins + (configId to state)) }
    }

    /**
     * Notes are the source of truth, so they are reapplied after loading a
     * snapshot, after a scan and after an explicit router login. That also
     * removes a label whose note was deleted.
     */
    private fun annotate(snapshot: ScanSnapshot, configs: List<RouterConfig>): ScanSnapshot {
        val notes = deviceNotes.load()
        val hosts = snapshot.hosts.map { deviceNotes.apply(it, notes) }
        val routers = snapshot.routers.map { router ->
            router.copy(clients = router.clients.map { deviceNotes.apply(it, notes) })
        }
        return snapshot.copy(
            hosts = hosts,
            routers = routers,
            graph = TopologyBuilder.build(snapshot.local, hosts, routers, configs),
        )
    }

    private fun RouterSnapshot.sameRouterAs(config: RouterConfig): Boolean =
        configId == config.id || (configId == null && host == config.host)

    fun checkUpdate() {
        if (_state.value.update.checking || _state.value.update.downloading) return
        viewModelScope.launch {
            _state.update {
                it.copy(update = it.update.copy(checking = true, message = null, file = null))
            }
            val mirrors = mirrors()
            val installedRun = CanaryManifest.installedRunNumber(
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            )
            when (val result = Updater.check(installedRun, mirrors)) {
                is UpdateResult.UpToDate -> _state.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            info = null,
                            message = "已是最新版本（${BuildConfig.VERSION_NAME}）",
                        ),
                    )
                }

                is UpdateResult.Available -> _state.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            info = result.info,
                            message = "发现新版本 ${result.info.versionName}",
                        ),
                    )
                }

                is UpdateResult.Failed -> _state.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            message = "检查更新失败：${result.message}",
                        ),
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = _state.value.update.info ?: return
        if (_state.value.update.downloading) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    update = it.update.copy(
                        downloading = true,
                        progress = 0f,
                        message = "正在下载 ${info.versionName}",
                    ),
                )
            }
            var lastError: String? = null
            for (mirror in mirrors()) {
                val result = Updater.download(info, mirror, getApplication<Application>().cacheDir) { progress ->
                    _state.update { it.copy(update = it.update.copy(progress = progress)) }
                }
                when (result) {
                    is DownloadResult.Success -> {
                        _state.update {
                            it.copy(
                                update = it.update.copy(
                                    downloading = false,
                                    progress = 1f,
                                    file = result.file,
                                    message = "下载完成，可以安装",
                                ),
                            )
                        }
                        return@launch
                    }

                    is DownloadResult.Failed -> {
                        lastError = "${mirror.label}：${result.message}"
                    }
                }
            }
            _state.update {
                it.copy(
                    update = it.update.copy(
                        downloading = false,
                        progress = -1f,
                        message = "下载失败：${lastError ?: "请稍后再试"}",
                    ),
                )
            }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(update = it.update.copy(info = null, message = null)) }
    }

    /** Surfaces the result of handing the APK to the system installer. */
    fun setUpdateMessage(message: String) {
        _state.update { it.copy(update = it.update.copy(message = message)) }
    }

    private fun mirrors(): List<Mirror> = buildList {
        add(Mirrors.resolve(_state.value.settings.updateMirrorPrefix))
        addAll(Mirrors.PRESETS)
    }.distinctBy { it.prefix }
}
