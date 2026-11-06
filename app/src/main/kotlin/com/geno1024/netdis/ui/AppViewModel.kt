package com.geno1024.netdis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.netdis.BuildConfig
import com.geno1024.netdis.model.AppSettings
import com.geno1024.netdis.model.RouterConfig
import com.geno1024.netdis.model.ScanProgress
import com.geno1024.netdis.model.ScanSnapshot
import com.geno1024.netdis.model.ScanStage
import com.geno1024.netdis.net.DeviceScanner
import com.geno1024.netdis.storage.RouterRepository
import com.geno1024.netdis.storage.SettingsStore
import com.geno1024.netdis.storage.SnapshotStore
import com.geno1024.netdis.update.CanaryInfo
import com.geno1024.netdis.update.CanaryManifest
import com.geno1024.netdis.update.DownloadResult
import com.geno1024.netdis.update.Mirror
import com.geno1024.netdis.update.Mirrors
import com.geno1024.netdis.update.UpdateResult
import com.geno1024.netdis.update.Updater
import kotlinx.coroutines.CancellationException
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

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val routers: List<RouterConfig> = emptyList(),
    val snapshot: ScanSnapshot? = null,
    val progress: ScanProgress = ScanProgress(),
    val scanning: Boolean = false,
    val error: String? = null,
    val update: UpdateUiState = UpdateUiState(),
) {
    val versionName: String get() = BuildConfig.VERSION_NAME
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val routerRepository = RouterRepository(application)
    private val snapshotStore = SnapshotStore(application)
    private val scanner = DeviceScanner(application)

    private val _state = MutableStateFlow(
        AppUiState(
            settings = settingsStore.load(),
            routers = routerRepository.load(),
            snapshot = snapshotStore.load(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

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
                val snapshot = scanner.scan(current.settings, current.routers) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                snapshotStore.save(snapshot)
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        scanning = false,
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
        _state.update { it.copy(settings = updated) }
    }

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

    private fun mirrors(): List<Mirror> = buildList {
        add(Mirrors.resolve(_state.value.settings.updateMirrorPrefix))
        addAll(Mirrors.PRESETS)
    }.distinctBy { it.prefix }
}
