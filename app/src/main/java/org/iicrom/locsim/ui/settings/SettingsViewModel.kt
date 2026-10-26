package org.iicrom.locsim.ui.settings

import android.app.Application
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iicrom.locsim.data.MapCacheRepository
import org.iicrom.locsim.data.MapCacheStats
import org.iicrom.locsim.data.MapPreferences
import org.iicrom.locsim.data.OfflineDownloadRequest
import org.iicrom.locsim.data.OfflineMapPackage
import org.iicrom.locsim.data.OfflineMapUtils
import org.iicrom.locsim.data.PlaceRepository
import org.iicrom.locsim.location.MockLocationSettings
import org.iicrom.locsim.location.MockLocationState
import org.iicrom.locsim.ui.map.OsmTileSource
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import java.io.File

data class SettingsAccess(
    val hasLocationPermission: Boolean = false,
    val locationServicesEnabled: Boolean = false,
    val mockAppSelected: Boolean = false,
    val checking: Boolean = true,
) {
    val ready: Boolean
        get() = !checking && hasLocationPermission && locationServicesEnabled && mockAppSelected
}

sealed interface OfflineDownloadState {
    data object Idle : OfflineDownloadState
    data object Preparing : OfflineDownloadState
    data class Downloading(
        val progress: Int,
        val total: Int,
        val currentZoom: Int,
        val zoomMin: Int,
        val zoomMax: Int,
    ) : OfflineDownloadState

    data class Success(
        val packageFile: OfflineMapPackage,
        val tiles: Int,
    ) : OfflineDownloadState

    data class Failed(val message: String) : OfflineDownloadState
}

val OfflineDownloadState.isBusy: Boolean
    get() = this is OfflineDownloadState.Preparing || this is OfflineDownloadState.Downloading

private data class PreparedDownload(
    val tempFile: File,
    val writer: SqliteArchiveTileWriter,
    val manager: CacheManager,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val cacheRepository = MapCacheRepository.get(application)
    private val placeRepository = PlaceRepository.get(application)
    private val mapPreferences = MapPreferences(application)

    val places = placeRepository.places

    private val _cacheStats = MutableStateFlow(MapCacheStats())
    val cacheStats: StateFlow<MapCacheStats> = _cacheStats.asStateFlow()

    private val _access = MutableStateFlow(SettingsAccess())
    val access: StateFlow<SettingsAccess> = _access.asStateFlow()

    private val _downloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState: StateFlow<OfflineDownloadState> = _downloadState.asStateFlow()

    private val _clearingCache = MutableStateFlow(false)
    val clearingCache: StateFlow<Boolean> = _clearingCache.asStateFlow()

    private var cacheStatsJob: Job? = null
    private var accessJob: Job? = null
    private val accessMutex = Mutex()
    private var downloadJob: Job? = null
    private var downloadTask: CacheManager.CacheManagerTask? = null
    private var preparedDownload: PreparedDownload? = null
    private var activeRequest: OfflineDownloadRequest? = null
    private var downloadGeneration = 0

    init {
        viewModelScope.launch(Dispatchers.IO) { cacheRepository.cleanupTempFiles() }
        refreshAccess()
        refreshCacheStats()
    }

    fun lastCenter(): Pair<Double, Double> {
        val center = mapPreferences.lastCenter()
        return center.latitude to center.longitude
    }

    fun refreshAccess() {
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            accessMutex.withLock {
                _access.update { it.copy(checking = true) }
                val app = getApplication<Application>()
                val hasPermission = MockLocationSettings.hasLocationPermission(app)
                val locationEnabled = MockLocationSettings.isLocationServicesEnabled(app)
                val status = MockLocationState.status.value
                val mockSelected = if (status.isRunning || status.isStarting) {
                    true
                } else {
                    withContext(Dispatchers.IO) {
                        MockLocationSettings.isMockLocationAppSelected(app, allowProbe = true)
                    }
                }
                _access.value = SettingsAccess(
                    hasLocationPermission = hasPermission,
                    locationServicesEnabled = locationEnabled,
                    mockAppSelected = mockSelected,
                    checking = false,
                )
            }
        }
    }

    fun refreshCacheStats() {
        cacheStatsJob?.cancel()
        cacheStatsJob = viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                runCatching { cacheRepository.stats() }.getOrDefault(MapCacheStats())
            }
            _cacheStats.value = stats
        }
    }

    fun startDownload(
        centerLatitude: Double,
        centerLongitude: Double,
        radiusKm: Int,
        maxZoom: Int,
    ) {
        if (_downloadState.value.isBusy) return
        val estimatedTiles = OfflineMapUtils.estimatedTiles(
            centerLatitude,
            centerLongitude,
            radiusKm,
            maxZoom,
        )
        if (estimatedTiles <= 0) {
            _downloadState.value = OfflineDownloadState.Failed("无法估算该区域的瓦片数量")
            return
        }
        if (estimatedTiles > OfflineMapUtils.MAX_TILES) {
            _downloadState.value = OfflineDownloadState.Failed(
                "预计 $estimatedTiles 张瓦片，超过单次 ${OfflineMapUtils.MAX_TILES} 张上限，请缩小范围或降低缩放级别"
            )
            return
        }
        val estimatedBytes = OfflineMapUtils.estimatedBytes(estimatedTiles)
        val usableSpace = availableStorageBytes()
        if (usableSpace in 1 until estimatedBytes * 2) {
            _downloadState.value = OfflineDownloadState.Failed(
                "存储空间不足：预计需要 ${MapCacheRepository.formatBytes(estimatedBytes)}，建议先清理空间"
            )
            return
        }

        val request = OfflineDownloadRequest(
            centerLatitude = centerLatitude,
            centerLongitude = centerLongitude,
            radiusKm = radiusKm,
            maxZoom = maxZoom,
            estimatedTiles = estimatedTiles,
        )
        activeRequest = request
        val generation = ++downloadGeneration
        _downloadState.value = OfflineDownloadState.Preparing

        downloadJob = viewModelScope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) {
                    cacheRepository.cleanupTempFiles()
                    val tempFile = cacheRepository.newTempPackageFile()
                    var writer: SqliteArchiveTileWriter? = null
                    try {
                        val createdWriter = SqliteArchiveTileWriter(tempFile.absolutePath)
                        writer = createdWriter
                        val manager = CacheManager(
                            OsmTileSource,
                            createdWriter,
                            OfflineMapUtils.MIN_ZOOM,
                            request.maxZoom,
                        )
                        PreparedDownload(tempFile, createdWriter, manager)
                    } catch (t: Throwable) {
                        runCatching { writer?.onDetach() }
                        tempFile.delete()
                        throw t
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (generation == downloadGeneration) {
                    _downloadState.value = OfflineDownloadState.Failed(
                        "无法创建离线地图包：${t.message ?: t.javaClass.simpleName}"
                    )
                }
                return@launch
            }

            if (generation != downloadGeneration) {
                runCatching { prepared.writer.onDetach() }
                prepared.tempFile.delete()
                return@launch
            }
            preparedDownload = prepared
            if (generation != downloadGeneration) {
                runCatching { prepared.writer.onDetach() }
                prepared.tempFile.delete()
                preparedDownload = null
                return@launch
            }

            try {
                val task = prepared.manager.downloadAreaAsyncNoUI(
                    getApplication(),
                    OfflineMapUtils.bounds(
                        request.centerLatitude,
                        request.centerLongitude,
                        request.radiusKm,
                    ),
                    OfflineMapUtils.MIN_ZOOM,
                    request.maxZoom,
                    createDownloadCallback(generation),
                )
                if (generation != downloadGeneration) {
                    task.cancel(true)
                } else {
                    downloadTask = task
                }
            } catch (t: Throwable) {
                if (generation == downloadGeneration) {
                    failDownload(
                        generation,
                        "启动离线下载失败：${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun availableStorageBytes(): Long {
        val dir = cacheRepository.offlinePackageDir()
        return runCatching {
            val manager = getApplication<Application>().getSystemService(StorageManager::class.java)
                ?: return dir.usableSpace
            val uuid = manager.getUuidForPath(dir)
            manager.getAllocatableBytes(uuid)
        }.getOrElse { dir.usableSpace }
    }

    private fun createDownloadCallback(generation: Int): CacheManager.CacheManagerCallback =
        object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                if (generation == downloadGeneration) completeDownload(generation)
            }

            override fun updateProgress(
                progress: Int,
                currentZoomLevel: Int,
                zoomMin: Int,
                zoomMax: Int,
            ) {
                if (generation != downloadGeneration) return
                val current = _downloadState.value
                val total = if (current is OfflineDownloadState.Downloading) {
                    current.total
                } else {
                    activeRequest?.estimatedTiles ?: progress
                }
                _downloadState.value = OfflineDownloadState.Downloading(
                    progress = progress.coerceAtLeast(0),
                    total = total.coerceAtLeast(progress),
                    currentZoom = currentZoomLevel,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                )
            }

            override fun downloadStarted() = Unit

            override fun setPossibleTilesInArea(total: Int) {
                if (generation != downloadGeneration) return
                _downloadState.value = OfflineDownloadState.Downloading(
                    progress = 0,
                    total = total,
                    currentZoom = OfflineMapUtils.MIN_ZOOM,
                    zoomMin = OfflineMapUtils.MIN_ZOOM,
                    zoomMax = activeRequest?.maxZoom ?: OfflineMapUtils.MIN_ZOOM,
                )
            }

            override fun onTaskFailed(errors: Int) {
                if (generation == downloadGeneration) {
                    failDownload(generation, "有 $errors 张瓦片下载失败，请检查网络后重试")
                }
            }
        }

    private fun completeDownload(generation: Int) {
        if (generation != downloadGeneration) return
        val prepared = preparedDownload ?: return
        val request = activeRequest
        preparedDownload = null
        downloadTask = null
        runCatching { prepared.writer.onDetach() }

        if (request == null) {
            prepared.tempFile.delete()
            _downloadState.value = OfflineDownloadState.Failed("下载信息丢失，请重试")
            return
        }

        val packageFile = runCatching {
            cacheRepository.finalizePackage(
                tempFile = prepared.tempFile,
                latitude = request.centerLatitude,
                longitude = request.centerLongitude,
                radiusKm = request.radiusKm,
                maxZoom = request.maxZoom,
            )
        }.getOrElse { t ->
            prepared.tempFile.delete()
            _downloadState.value = OfflineDownloadState.Failed(
                "保存离线地图包失败：${t.message ?: t.javaClass.simpleName}"
            )
            return
        }

        val completedTiles = (_downloadState.value as? OfflineDownloadState.Downloading)
            ?.total
            ?: request.estimatedTiles
        _downloadState.value = OfflineDownloadState.Success(
            packageFile = packageFile,
            tiles = completedTiles,
        )
        refreshCacheStats()
    }

    private fun failDownload(generation: Int, message: String) {
        if (generation != downloadGeneration) return
        downloadTask = null
        val prepared = preparedDownload
        preparedDownload = null
        runCatching { prepared?.writer?.onDetach() }
        prepared?.tempFile?.delete()
        _downloadState.value = OfflineDownloadState.Failed(message)
        refreshCacheStats()
    }

    fun cancelDownload() {
        downloadGeneration++
        downloadJob?.cancel()
        downloadJob = null
        downloadTask?.cancel(true)
        downloadTask = null
        val prepared = preparedDownload
        preparedDownload = null
        runCatching { prepared?.writer?.onDetach() }
        prepared?.tempFile?.delete()
        activeRequest = null
        _downloadState.value = OfflineDownloadState.Idle
        refreshCacheStats()
    }

    fun resetDownloadState() {
        if (!_downloadState.value.isBusy) {
            _downloadState.value = OfflineDownloadState.Idle
        }
    }

    fun deletePackage(packageFile: OfflineMapPackage) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheRepository.deletePackage(packageFile.file)
            refreshCacheStats()
        }
    }

    fun clearOnlineCache() {
        if (_clearingCache.value) return
        _clearingCache.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cacheRepository.clearOnlineCache() }
            } finally {
                _clearingCache.value = false
                refreshCacheStats()
            }
        }
    }

    override fun onCleared() {
        downloadGeneration++
        downloadJob?.cancel()
        downloadTask?.cancel(true)
        runCatching { preparedDownload?.writer?.onDetach() }
        preparedDownload?.tempFile?.delete()
        preparedDownload = null
        super.onCleared()
    }
}
