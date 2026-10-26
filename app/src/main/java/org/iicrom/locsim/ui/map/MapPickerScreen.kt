@file:Suppress("AutoboxingStateCreation")

package org.iicrom.locsim.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.iicrom.locsim.R
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.util.Geo
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@Composable
fun MapPickerScreen(
    editingPlace: SavedPlace?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSaved: (SavedPlace, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: MapPickerViewModel = viewModel()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0

    val initialPoint = remember { viewModel.initialCenter(editingPlace) }
    var selectedLat by rememberSaveable { mutableStateOf(initialPoint.latitude) }
    var selectedLng by rememberSaveable { mutableStateOf(initialPoint.longitude) }
    var address by rememberSaveable { mutableStateOf(editingPlace?.address.orEmpty()) }
    var addressLoading by remember { mutableStateOf(false) }
    var addressFailed by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var resultsVisible by remember { mutableStateOf(false) }

    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showCoordinateDialog by rememberSaveable { mutableStateOf(false) }
    var saveInitialName by rememberSaveable { mutableStateOf("") }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(OsmTileSource)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(selectedLat, selectedLng))
        }
    }

    val marker = remember {
        Marker(mapView).apply {
            position = GeoPoint(selectedLat, selectedLng)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            isDraggable = true
        }
    }

    fun moveTo(point: GeoPoint, zoom: Double? = null) {
        selectedLat = point.latitude
        selectedLng = point.longitude
        marker.position = point
        mapView.controller.animateTo(point, zoom ?: mapView.zoomLevelDouble, 500L)
        mapView.invalidate()
    }

    LifecycleResumeEffect(mapView) {
        mapView.onResume()
        onPauseOrDispose { mapView.onPause() }
    }

    DisposableEffect(mapView, marker) {
        // 先加事件层、后加图钉：事件分发按逆序进行，图钉的点击/长按拖动优先，
        // 空白地图区域的点选交给 MapEventsOverlay。
        mapView.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    selectedLat = p.latitude
                    selectedLng = p.longitude
                    marker.position = p
                    mapView.invalidate()
                    resultsVisible = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
        )
        mapView.overlays.add(marker)
        // 不在此时清空 overlays：MapView.onDetach() 会负责释放 Marker 图标等资源。
        onDispose { }
    }

    DisposableEffect(marker) {
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) {
                selectedLat = marker.position.latitude
                selectedLng = marker.position.longitude
            }

            override fun onMarkerDragEnd(marker: Marker) {
                selectedLat = marker.position.latitude
                selectedLng = marker.position.longitude
            }
        })
        onDispose { marker.setOnMarkerDragListener(null) }
    }

    // 拖动/点选后同步图钉（例如通过搜索或坐标输入改变选中点时）。
    LaunchedEffect(selectedLat, selectedLng) {
        val point = GeoPoint(selectedLat, selectedLng)
        if (marker.position.latitude != point.latitude || marker.position.longitude != point.longitude) {
            marker.position = point
            mapView.invalidate()
        }
    }

    // 防抖逆地理编码，避免拖动图钉时频繁请求。
    LaunchedEffect(selectedLat, selectedLng) {
        addressLoading = true
        addressFailed = false
        delay(450)
        val resolved = viewModel.reverseGeocode(selectedLat, selectedLng)
        if (!resolved.isNullOrBlank()) {
            address = resolved
        } else {
            val moved = kotlin.math.abs(selectedLat - initialPoint.latitude) > 1e-7 ||
                kotlin.math.abs(selectedLng - initialPoint.longitude) > 1e-7
            if (moved) address = ""
            addressFailed = true
        }
        addressLoading = false
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            results = emptyList()
            resultsVisible = false
            searching = false
            searchMessage = null
            return@LaunchedEffect
        }
        delay(550)
        searching = true
        resultsVisible = true
        searchMessage = null
        viewModel.search(trimmed)
            .onSuccess { list ->
                results = list
                if (list.isEmpty()) searchMessage = "没有找到匹配的地点"
            }
            .onFailure {
                results = emptyList()
                searchMessage = it.message ?: "搜索失败，请检查网络后重试"
            }
        searching = false
    }

    BackHandler {
        when {
            showCoordinateDialog -> showCoordinateDialog = false
            showSaveDialog -> showSaveDialog = false
            query.isNotBlank() || keyboardVisible -> {
                query = ""
                resultsVisible = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }

            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                val center = view.mapCenter
                viewModel.rememberCenter(center.latitude, center.longitude)
                view.onDetach()
            },
        )

        if (isSystemInDarkTheme()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (it.isFocused && query.length >= 2) resultsVisible = true
                            },
                        placeholder = { Text("搜索地点或输入坐标") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        query = ""
                                        results = emptyList()
                                        resultsVisible = false
                                        searchMessage = null
                                    }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "清空")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                    )
                }
            }

            val showPanel = resultsVisible && query.trim().length >= 2 &&
                (searching || results.isNotEmpty() || searchMessage != null)
            AnimatedVisibility(visible = showPanel) {
                SearchResultsPanel(
                    searching = searching,
                    message = searchMessage,
                    results = results,
                    onSelect = { item ->
                        query = ""
                        results = emptyList()
                        resultsVisible = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        moveTo(GeoPoint(item.latitude, item.longitude), 17.0)
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapControlButton(
                contentDescription = "放大",
                onClick = { mapView.controller.zoomIn() },
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            MapControlButton(
                contentDescription = "缩小",
                onClick = { mapView.controller.zoomOut() },
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            MapControlButton(
                contentDescription = "回到当前位置",
                onClick = {
                    val point = viewModel.lastKnownLocation()
                    if (point != null) {
                        moveTo(point, 17.0)
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar("暂未获取到当前位置，请直接在地图上选点")
                        }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (!keyboardVisible) {
            SelectionCard(
                modifier = Modifier.align(Alignment.BottomCenter),
                address = address,
                addressLoading = addressLoading,
                addressFailed = addressFailed,
                latitude = selectedLat,
                longitude = selectedLng,
                editing = editingPlace != null,
                onInputCoordinates = { showCoordinateDialog = true },
                onSave = {
                    saveInitialName = editingPlace?.name ?: if (address.isNotBlank()) {
                        Geo.suggestedName(address)
                    } else {
                        "我的地点"
                    }
                    showSaveDialog = true
                },
                onOpenAttribution = {
                    uriHandler.openUri("https://www.openstreetmap.org/copyright")
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (keyboardVisible) 16.dp else 236.dp,
                ),
        )
    }

    if (showSaveDialog) {
        SavePlaceDialog(
            initialName = saveInitialName,
            address = address,
            latitude = selectedLat,
            longitude = selectedLng,
            editing = editingPlace != null,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                scope.launch {
                    val saved = viewModel.savePlace(
                        existingId = editingPlace?.id,
                        name = name,
                        address = address,
                        latitude = selectedLat,
                        longitude = selectedLng,
                    )
                    viewModel.rememberCenter(selectedLat, selectedLng)
                    onSaved(saved, editingPlace == null)
                }
            },
        )
    }

    if (showCoordinateDialog) {
        CoordinateDialog(
            initialLatitude = selectedLat,
            initialLongitude = selectedLng,
            onDismiss = { showCoordinateDialog = false },
            onConfirm = { latitude, longitude ->
                showCoordinateDialog = false
                moveTo(GeoPoint(latitude, longitude), 17.0)
            },
        )
    }
}

@Composable
private fun MapControlButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(46.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun SearchResultsPanel(
    searching: Boolean,
    message: String?,
    results: List<GeoPlace>,
    onSelect: (GeoPlace) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        when {
            searching -> {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在搜索…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            results.isEmpty() -> {
                Text(
                    text = message ?: "没有找到匹配的地点",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(results) { index, item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.address.isNotBlank()) {
                                Text(
                                    text = item.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = Geo.formatLatLng(item.latitude, item.longitude),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (index != results.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionCard(
    modifier: Modifier = Modifier,
    address: String,
    addressLoading: Boolean,
    addressFailed: Boolean,
    latitude: Double,
    longitude: Double,
    editing: Boolean,
    onInputCoordinates: () -> Unit,
    onSave: () -> Unit,
    onOpenAttribution: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = address.ifBlank { "已选择位置" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        addressLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                )
                                Text(
                                    "正在解析地址…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        addressFailed && address.isBlank() -> {
                            Text(
                                "无法解析地址，可直接保存或输入名称",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = onInputCoordinates) { Text("坐标") }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = Geo.formatLatLng(latitude, longitude),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "WGS-84",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = if (editing) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (editing) "保存修改" else "保存地点")
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "地图数据 © OpenStreetMap 贡献者 · OSM France",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(onClick = onOpenAttribution),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "点击地图或长按图钉调整",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun SavePlaceDialog(
    initialName: String,
    address: String,
    latitude: Double,
    longitude: Double,
    editing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName.take(30)) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "编辑地点" else "添加地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
                    supportingText = {
                        Text(
                            text = if (trimmed.isEmpty()) "名称不能为空" else "${name.length}/30",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = Geo.formatLatLng(latitude, longitude),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (address.isNotBlank()) {
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    text = if (editing) "保存后会更新列表中的地点。" else "保存后会回到地点列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun CoordinateDialog(
    initialLatitude: Double,
    initialLongitude: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit,
) {
    var latitudeText by rememberSaveable { mutableStateOf(Geo.formatLatitude(initialLatitude)) }
    var longitudeText by rememberSaveable { mutableStateOf(Geo.formatLongitude(initialLongitude)) }
    val latitude = latitudeText.trim().toDoubleOrNull()
    val longitude = longitudeText.trim().toDoubleOrNull()
    val latitudeValid = latitude != null && Geo.isValidLatitude(latitude)
    val longitudeValid = longitude != null && Geo.isValidLongitude(longitude)
    val valid = latitudeValid && longitudeValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入坐标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "WGS-84 十进制度，纬度在前、经度在后。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    label = { Text("纬度（-90 ~ 90）") },
                    singleLine = true,
                    isError = latitudeText.isNotBlank() && !latitudeValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = longitudeText,
                    onValueChange = { longitudeText = it },
                    label = { Text("经度（-180 ~ 180）") },
                    singleLine = true,
                    isError = longitudeText.isNotBlank() && !longitudeValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid && latitudeText.isNotBlank() && longitudeText.isNotBlank()) {
                    Text(
                        "坐标格式不正确或超出范围",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (latitude != null && longitude != null) onConfirm(latitude, longitude)
                },
                enabled = valid,
            ) { Text("定位") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
