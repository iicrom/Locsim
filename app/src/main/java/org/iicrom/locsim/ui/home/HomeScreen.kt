package org.iicrom.locsim.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.location.MockLocationSettings
import org.iicrom.locsim.location.MockLocationStatus
import org.iicrom.locsim.util.Geo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    places: List<SavedPlace>,
    snackbarHostState: SnackbarHostState,
    onAddPlace: () -> Unit,
    onEditPlace: (SavedPlace) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HomeViewModel = viewModel()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val mock by viewModel.mockStatus.collectAsStateWithLifecycle()

    var pendingStartId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSetupDialog by rememberSaveable { mutableStateOf(false) }
    var showHelpDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedPlace?>(null) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.refreshAccess()
        if (!granted) {
            scope.launch {
                val action = snackbarHostState.showSnackbar(
                    message = "需要定位权限才能运行模拟服务",
                    actionLabel = "去设置",
                    withDismissAction = true,
                )
                if (action == SnackbarResult.ActionPerformed) {
                    MockLocationSettings.openAppSettings(context)
                }
            }
        }
    }

    val requestStart: (SavedPlace) -> Unit = { place ->
        if (!access.checking) {
            when {
                access.ready -> {
                    pendingStartId = null
                    viewModel.startMock(place)
                }

                !access.hasLocationPermission -> {
                    pendingStartId = place.id
                    permissionLauncher.launch(permissions)
                }

                !access.locationServicesEnabled -> {
                    pendingStartId = place.id
                    MockLocationSettings.openLocationSettings(context)
                    scope.launch {
                        snackbarHostState.showSnackbar("打开系统定位开关后会自动开始模拟")
                    }
                }

                else -> {
                    pendingStartId = place.id
                    showSetupDialog = true
                }
            }
        }
    }

    // 服务启动失败后重新检查权限/开关，让设置清单立即给出正确指引。
    LaunchedEffect(mock.error) {
        if (mock.error != null) viewModel.refreshAccess()
    }

    // 等权限 / 定位开关 / 模拟位置应用都就绪后，继续用户刚才发起的模拟。
    LaunchedEffect(access.ready, pendingStartId) {
        if (access.ready && pendingStartId != null) {
            val place = places.firstOrNull { it.id == pendingStartId }
            pendingStartId = null
            showSetupDialog = false
            if (place != null) viewModel.startMock(place)
        }
    }

    // 权限刚授予、但模拟位置应用尚未选择时，自动弹出设置引导。
    LaunchedEffect(
        access.hasLocationPermission,
        access.locationServicesEnabled,
        access.mockAppSelected,
        pendingStartId,
    ) {
        if (
            pendingStartId != null &&
            access.hasLocationPermission &&
            access.locationServicesEnabled &&
            !access.mockAppSelected
        ) {
            showSetupDialog = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Locsim", fontWeight = FontWeight.Bold)
                        Text(
                            text = "定点位置模拟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "使用说明")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPlace,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加地点") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!access.checking && !access.ready) {
                item(key = "setup") {
                    SetupCard(
                        access = access,
                        onRequestPermission = { permissionLauncher.launch(permissions) },
                        onOpenLocationSettings = {
                            MockLocationSettings.openLocationSettings(context)
                        },
                        onOpenDeveloperOptions = {
                            MockLocationSettings.openDeveloperOptions(context)
                        },
                    )
                }
            }

            val error = mock.error
            if (!error.isNullOrBlank()) {
                item(key = "error") {
                    ErrorCard(
                        message = error,
                        hasPermission = access.hasLocationPermission,
                        locationEnabled = access.locationServicesEnabled,
                        mockAppSelected = access.mockAppSelected,
                        onOpenAppSettings = { MockLocationSettings.openAppSettings(context) },
                        onOpenLocationSettings = {
                            MockLocationSettings.openLocationSettings(context)
                        },
                        onOpenDeveloperOptions = {
                            MockLocationSettings.openDeveloperOptions(context)
                        },
                        onDismiss = viewModel::clearError,
                    )
                }
            }

            if (mock.isRunning || mock.isStarting) {
                item(key = "active") {
                    ActiveSimulationCard(
                        status = mock,
                        places = places,
                        onStop = viewModel::stopMock,
                    )
                }
            }

            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("保存的地点", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${places.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "点击地点开始模拟，再次点击停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (places.isEmpty() && !access.checking) {
                item(key = "empty") {
                    EmptyState(onAddPlace = onAddPlace)
                }
            }

            items(places, key = { it.id }) { place ->
                val active = mock.placeId == place.id && (mock.isRunning || mock.isStarting)
                PlaceCard(
                    place = place,
                    active = active,
                    starting = active && mock.isStarting,
                    actionEnabled = !access.checking,
                    onClick = {
                        if (active) viewModel.stopMock() else requestStart(place)
                    },
                    onEdit = { onEditPlace(place) },
                    onRename = { renameTarget = place },
                    onDelete = { deleteTarget = place },
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            place = target,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.rename(target, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("删除地点") },
            text = { Text("确定删除「${target.name}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target)
                        deleteTarget = null
                        scope.launch {
                            snackbarHostState.showSnackbar("已删除「${target.name}」")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = {
                showSetupDialog = false
                pendingStartId = null
            },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            title = { Text("选择模拟位置信息应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请按以下步骤操作：")
                    Text("1. 打开系统「设置 → 开发者选项」")
                    Text("2. 找到「选择模拟位置信息应用」")
                    Text("3. 选择 Locsim，然后返回应用")
                    Text(
                        text = "返回后会自动开始模拟。若找不到该选项，请先在「关于手机」中连续点击版本号开启开发者选项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSetupDialog = false
                        MockLocationSettings.openDeveloperOptions(context)
                    }
                ) { Text("去开发者选项") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSetupDialog = false
                        pendingStartId = null
                    }
                ) { Text("稍后") }
            },
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

@Composable
private fun SetupCard(
    access: HomeAccess,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Column {
                    Text("完成设置后开始模拟", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "首次使用需要授予定位权限，并选择模拟位置应用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f))
            SetupRow(
                done = access.hasLocationPermission,
                title = "定位权限",
                description = if (access.hasLocationPermission) "已授予" else "运行前台定位服务需要此权限",
                actionLabel = "授予",
                onAction = onRequestPermission,
            )
            SetupRow(
                done = access.locationServicesEnabled,
                title = "系统定位",
                description = if (access.locationServicesEnabled) "已开启" else "请打开系统定位开关",
                actionLabel = "去开启",
                onAction = onOpenLocationSettings,
            )
            SetupRow(
                done = access.mockAppSelected,
                title = "模拟位置应用",
                description = if (access.mockAppSelected) {
                    "已选择 Locsim"
                } else {
                    "开发者选项 → 选择模拟位置信息应用"
                },
                actionLabel = "去设置",
                onAction = onOpenDeveloperOptions,
            )
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
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
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
        }
        if (done) {
            Text(
                "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    hasPermission: Boolean,
    locationEnabled: Boolean,
    mockAppSelected: Boolean,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actionLabel: String
    val action: () -> Unit
    when {
        !hasPermission -> {
            actionLabel = "权限设置"
            action = onOpenAppSettings
        }

        !locationEnabled -> {
            actionLabel = "去开启定位"
            action = onOpenLocationSettings
        }

        !mockAppSelected -> {
            actionLabel = "开发者选项"
            action = onOpenDeveloperOptions
        }

        else -> {
            actionLabel = "开发者选项"
            action = onOpenDeveloperOptions
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("模拟未能启动", style = MaterialTheme.typography.titleSmall)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                TextButton(onClick = action) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ActiveSimulationCard(
    status: MockLocationStatus,
    places: List<SavedPlace>,
    onStop: () -> Unit,
) {
    val name = places.firstOrNull { it.id == status.placeId }?.name ?: status.placeName
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (status.isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (status.isStarting) "正在启动模拟…" else "正在模拟位置",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = name.ifBlank { "已保存地点" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Geo.formatLatLng(status.latitude, status.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            FilledTonalButton(onClick = onStop) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("停止")
            }
        }
    }
}

@Composable
private fun PlaceCard(
    place: SavedPlace,
    active: Boolean,
    starting: Boolean,
    actionEnabled: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val secondaryTextColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = onClick,
        enabled = actionEnabled,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = if (active) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (active) 0.dp else 1.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        StatusBadge(if (starting) "启动中" else "模拟中")
                    }
                }
                if (place.address.isNotBlank()) {
                    Text(
                        text = place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Geo.formatLatLng(place.latitude, place.longitude),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryTextColor,
                )
            }
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = onClick,
                    enabled = actionEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (active) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = if (active) "停止模拟" else "开始模拟",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("在地图上编辑") },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyState(onAddPlace: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Text("还没有保存的地点", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "去地图上选一个位置，保存后就能在列表中一键开始模拟。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(2.dp))
        OutlinedButton(onClick = onAddPlace) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("去地图选点")
        }
    }
}

@Composable
private fun RenameDialog(
    place: SavedPlace,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(place.id) { mutableStateOf(place.name.take(30)) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名地点") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("名称") },
                singleLine = true,
                isError = trimmed.isEmpty(),
                supportingText = { Text("${name.length}/30") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text("使用说明") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "1. 授予定位权限，并在开发者选项中将 Locsim 选为「模拟位置信息应用」。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "2. 点“添加地点”，在地图上点击、长按拖动图钉或搜索地点，保存后回到列表。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "3. 点击列表中的地点开始模拟；再次点击或点“停止”结束。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "4. 在“设置 → 地图与离线地图”中可以下载当前区域周边的离线瓦片；下载期间请保持应用在前台。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "5. 地图和模拟坐标均使用 WGS-84。部分应用会忽略模拟位置，或使用 Wi-Fi、基站辅助定位，效果取决于目标应用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "地图数据 © OpenStreetMap 贡献者（OSM France / HOT 镜像）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
