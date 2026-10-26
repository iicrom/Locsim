@file:Suppress("AutoboxingStateCreation")

package org.iicrom.locsim.ui.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.iicrom.locsim.R
import org.iicrom.locsim.data.MapCacheStats
import org.iicrom.locsim.data.MapCacheRepository
import org.iicrom.locsim.data.OfflineMapPackage
import org.iicrom.locsim.data.OfflineMapUtils
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.location.MockLocationSettings
import org.iicrom.locsim.util.Geo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val viewModel: SettingsViewModel = viewModel()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val stats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val clearingCache by viewModel.clearingCache.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    var showDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var packageToDelete by remember { mutableStateOf<OfflineMapPackage?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val busy = downloadState.isBusy

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccess()
                viewModel.refreshCacheStats()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.refreshAccess()
        if (!granted) {
            scope.launch {
                val action = snackbarHostState.showSnackbar(
                    message = "未授予定位权限",
                    actionLabel = "应用设置",
                    withDismissAction = true,
                )
                if (action == SnackbarResult.ActionPerformed) {
                    MockLocationSettings.openAppSettings(context)
                }
            }
        }
    }

    val requestBack: () -> Unit = {
        if (busy) showExitConfirm = true else onBack()
    }

    BackHandler { requestBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "map_section") { SectionHeader("地图与离线地图") }
            item(key = "offline_packages") {
                OfflinePackagesCard(
                    stats = stats,
                    downloadBusy = busy,
                    onDownload = {
                        viewModel.resetDownloadState()
                        showDownloadDialog = true
                    },
                    onDelete = { packageToDelete = it },
                )
            }
            item(key = "online_cache") {
                OnlineCacheCard(
                    stats = stats,
                    clearing = clearingCache,
                    onClear = { showClearConfirm = true },
                )
            }

            item(key = "simulation_section") { SectionHeader("定位模拟") }
            item(key = "permissions") {
                PermissionsCard(
                    access = access,
                    onGrantPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    onOpenDeveloperOptions = {
                        MockLocationSettings.openDeveloperOptions(context)
                    },
                    onOpenLocationSettings = {
                        MockLocationSettings.openLocationSettings(context)
                    },
                    onOpenAppSettings = { MockLocationSettings.openAppSettings(context) },
                )
            }

            item(key = "about_section") { SectionHeader("关于") }
            item(key = "about") {
                AboutCard(
                    versionName = versionName,
                    onOpenOsmCopyright = {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    },
                )
            }
        }
    }

    if (showDownloadDialog) {
        DownloadOfflineDialog(
            places = places,
            lastCenter = remember { viewModel.lastCenter() },
            state = downloadState,
            onStart = { latitude, longitude, radiusKm, maxZoom ->
                viewModel.startDownload(latitude, longitude, radiusKm, maxZoom)
            },
            onCancel = viewModel::cancelDownload,
            onDismiss = {
                showDownloadDialog = false
                viewModel.resetDownloadState()
            },
            onDone = {
                showDownloadDialog = false
                viewModel.resetDownloadState()
                scope.launch {
                    snackbarHostState.showSnackbar("离线地图包已保存，下次打开地图时会自动使用")
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            title = { Text("清除在线缓存") },
            text = {
                Text(
                    "将删除 ${stats.onlineTileCount} 张在线瓦片（${MapCacheRepository.formatBytes(stats.onlineBytes)}）。" +
                        "离线地图包不会被删除，再次浏览地图时会重新下载瓦片。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearOnlineCache()
                    }
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    packageToDelete?.let { packageFile ->
        AlertDialog(
            onDismissRequest = { packageToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("删除离线地图包") },
            text = {
                Text(
                    "确定删除 ${formatPackageTitle(packageFile)} 吗？" +
                        "删除后该区域的离线瓦片将不可用。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePackage(packageFile)
                        packageToDelete = null
                        scope.launch { snackbarHostState.showSnackbar("已删除离线地图包") }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { packageToDelete = null }) { Text("取消") }
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("离线地图正在下载") },
            text = { Text("离开设置会取消当前下载。请保持应用在前台，下载完成后再离开。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        viewModel.cancelDownload()
                        onBack()
                    }
                ) { Text("取消下载并退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续下载") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun OfflinePackagesCard(
    stats: MapCacheStats,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
    onDelete: (OfflineMapPackage) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("离线地图包", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "下载后可断网浏览选定区域",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "以选中的地点或最近地图位置为中心，缓存 OSM 瓦片到应用内部存储。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (stats.offlinePackages.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "已下载 ${stats.offlinePackages.size} 个 · ${MapCacheRepository.formatBytes(stats.offlineBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                stats.offlinePackages.forEachIndexed { index, packageFile ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatPackageTitle(packageFile),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatPackageDetails(packageFile),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = MapCacheRepository.formatBytes(packageFile.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        IconButton(
                            onClick = { onDelete(packageFile) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除离线地图包",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (index != stats.offlinePackages.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                Text(
                    text = "还没有离线地图包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = onDownload,
                enabled = !downloadBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (stats.offlinePackages.isEmpty()) "下载离线地图包" else "再下载一个区域")
            }

            Text(
                text = "瓦片来自 OpenStreetMap France，仅建议个人离线查看，请勿批量抓取。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun OnlineCacheCard(
    stats: MapCacheStats,
    clearing: Boolean,
    onClear: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("在线瓦片缓存", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "${stats.onlineTileCount} 张瓦片 · ${MapCacheRepository.formatBytes(stats.onlineBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "浏览地图时自动缓存，可加快再次加载。清除后再次浏览会重新下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (clearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在清理…", style = MaterialTheme.typography.labelMedium)
                } else {
                    TextButton(
                        onClick = onClear,
                        enabled = stats.onlineTileCount > 0,
                    ) { Text("清除在线缓存") }
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    access: SettingsAccess,
    onGrantPermission: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("系统权限与开关", style = MaterialTheme.typography.titleMedium)
            }

            if (access.ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "模拟定位已就绪",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            PermissionRow(
                done = access.hasLocationPermission,
                title = "定位权限",
                description = "前台定位服务需要定位权限",
                actionLabel = "授予",
                enabled = !access.checking,
                onAction = onGrantPermission,
                onOpenSettings = onOpenAppSettings,
            )
            PermissionRow(
                done = access.locationServicesEnabled,
                title = "系统定位",
                description = "需要打开系统定位开关",
                actionLabel = "去开启",
                enabled = !access.checking,
                onAction = onOpenLocationSettings,
                onOpenSettings = onOpenLocationSettings,
            )
            PermissionRow(
                done = access.mockAppSelected,
                title = "模拟位置应用",
                description = "开发者选项 → 选择模拟位置信息应用 → Locsim",
                actionLabel = "去设置",
                enabled = !access.checking,
                onAction = onOpenDeveloperOptions,
                onOpenSettings = onOpenDeveloperOptions,
            )
            Text(
                text = "修改系统设置后返回本页会自动刷新。没有“模拟位置信息应用”选项时，请先开启开发者选项。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    done: Boolean,
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (done) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = if (done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (done) {
            Text(
                "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Row {
                TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                if (actionLabel != "去设置") {
                    IconButton(
                        onClick = onOpenSettings,
                        enabled = enabled,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "打开系统设置",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    versionName: String,
    onOpenOsmCopyright: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AboutRow("版本", versionName)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            AboutRow("瓦片源", "OpenStreetMap France（HOT 镜像）")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            AboutRow("坐标体系", "WGS-84")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("地图数据", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "OpenStreetMap 贡献者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenOsmCopyright),
                )
            }
            Text(
                text = "Locsim 仅用于学习、开发调试和位置测试。模拟定位可能被目标应用识别，请遵守相关服务条款。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class CenterOption(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadOfflineDialog(
    places: List<SavedPlace>,
    lastCenter: Pair<Double, Double>,
    state: OfflineDownloadState,
    onStart: (Double, Double, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val centerOptions = remember(places, lastCenter) {
        buildList {
            add(
                CenterOption(
                    label = "最近地图位置",
                    latitude = lastCenter.first,
                    longitude = lastCenter.second,
                )
            )
            places.forEach { place ->
                add(
                    CenterOption(
                        label = place.name,
                        latitude = place.latitude,
                        longitude = place.longitude,
                    )
                )
            }
        }
    }
    var selectedCenterIndex by rememberSaveable { mutableStateOf(0) }
    var radiusKm by rememberSaveable { mutableStateOf(5) }
    var maxZoom by rememberSaveable { mutableStateOf(16) }
    var centerMenuExpanded by remember { mutableStateOf(false) }

    val selectedCenter = centerOptions.getOrElse(selectedCenterIndex) { centerOptions.first() }
    val estimatedTiles = remember(selectedCenter, radiusKm, maxZoom) {
        OfflineMapUtils.estimatedTiles(
            selectedCenter.latitude,
            selectedCenter.longitude,
            radiusKm,
            maxZoom,
        )
    }
    val estimatedBytes = remember(estimatedTiles) {
        OfflineMapUtils.estimatedBytes(estimatedTiles)
    }
    val overLimit = estimatedTiles > OfflineMapUtils.MAX_TILES
    val busy = state.isBusy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
            )
        },
        title = { Text("下载离线地图包") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is OfflineDownloadState.Success -> {
                        Text(
                            "下载完成，共约 ${state.tiles} 张瓦片，文件大小 ${MapCacheRepository.formatBytes(state.packageFile.sizeBytes)}。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "下次打开地图时会自动使用这个离线包；断网时也能浏览该区域。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is OfflineDownloadState.Preparing, is OfflineDownloadState.Downloading -> {
                        val progressState = state as? OfflineDownloadState.Downloading
                        val fraction = if (progressState != null && progressState.total > 0) {
                            progressState.progress.toFloat() / progressState.total
                        } else {
                            0f
                        }
                        if (progressState == null || progressState.total <= 0) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = if (progressState != null && progressState.total > 0) {
                                "正在下载 ${progressState.progress} / ${progressState.total} 张（缩放 ${progressState.currentZoom}）"
                            } else {
                                "正在准备离线瓦片…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "下载期间请保持应用在前台。单次最多 ${OfflineMapUtils.MAX_TILES} 张瓦片。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            "下载所选区域周边的 OSM 瓦片，保存为应用内部离线包。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "中心点",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            OutlinedButton(
                                onClick = { centerMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = selectedCenter.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = Geo.formatLatLng(
                                        selectedCenter.latitude,
                                        selectedCenter.longitude,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = centerMenuExpanded,
                                onDismissRequest = { centerMenuExpanded = false },
                            ) {
                                centerOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(option.label)
                                                Text(
                                                    Geo.formatLatLng(option.latitude, option.longitude),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedCenterIndex = index
                                            centerMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Text(
                            "提示：在地图页移动位置后返回设置，可更新“最近地图位置”。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )

                        Text(
                            "下载半径",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OfflineMapUtils.radiusOptions.forEach { radius ->
                                FilterChip(
                                    selected = radiusKm == radius,
                                    onClick = { radiusKm = radius },
                                    label = { Text("$radius km") },
                                )
                            }
                        }

                        Text(
                            "最高缩放",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OfflineMapUtils.maxZoomOptions.forEach { zoom ->
                                FilterChip(
                                    selected = maxZoom == zoom,
                                    onClick = { maxZoom = zoom },
                                    label = { Text("z$zoom") },
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (overLimit) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "预计 $estimatedTiles 张瓦片 · 约 ${MapCacheRepository.formatBytes(estimatedBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (overLimit) {
                                    Text(
                                        text = "超过单次 ${OfflineMapUtils.MAX_TILES} 张上限，请缩小半径或降低缩放级别。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }

                        val error = (state as? OfflineDownloadState.Failed)?.message
                        if (!error.isNullOrBlank()) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            "瓦片来自 OpenStreetMap France，仅建议个人离线使用。下载会占用应用内部存储。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state is OfflineDownloadState.Success -> {
                    TextButton(onClick = onDone) { Text("完成") }
                }

                busy -> {
                    TextButton(onClick = onCancel) { Text("取消下载") }
                }

                else -> {
                    TextButton(
                        onClick = {
                            onStart(
                                selectedCenter.latitude,
                                selectedCenter.longitude,
                                radiusKm,
                                maxZoom,
                            )
                        },
                        enabled = !overLimit && estimatedTiles > 0,
                    ) { Text("开始下载") }
                }
            }
        },
        dismissButton = {
            if (!busy && state !is OfflineDownloadState.Success) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

private fun formatPackageTitle(packageFile: OfflineMapPackage): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(packageFile.downloadedAt))
    return "离线地图 · $date"
}

private fun formatPackageDetails(packageFile: OfflineMapPackage): String {
    val latitude = packageFile.centerLatitude
    val longitude = packageFile.centerLongitude
    val radius = packageFile.radiusKm
    val maxZoom = packageFile.maxZoom
    if (latitude == null || longitude == null || radius == null || maxZoom == null) {
        return "离线地图瓦片包"
    }
    return "半径 $radius km · z${OfflineMapUtils.MIN_ZOOM}–$maxZoom · " +
        Geo.formatLatLng(latitude, longitude)
}
