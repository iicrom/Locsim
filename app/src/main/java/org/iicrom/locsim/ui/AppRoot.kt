package org.iicrom.locsim.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.iicrom.locsim.data.PlaceRepository
import org.iicrom.locsim.location.MockLocationState
import org.iicrom.locsim.ui.home.HomeScreen
import org.iicrom.locsim.ui.map.MapPickerScreen
import org.iicrom.locsim.ui.settings.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_MAP = "map"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repository = remember(context) { PlaceRepository.get(context) }
    val places by repository.places.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMap by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    val route = when {
        showMap -> ROUTE_MAP
        showSettings -> ROUTE_SETTINGS
        else -> ROUTE_HOME
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(tween(durationMillis = 220)) togetherWith fadeOut(tween(durationMillis = 160))
            },
            label = "screen",
        ) { currentRoute ->
            when (currentRoute) {
                ROUTE_MAP -> MapPickerScreen(
                    editingPlace = editingId?.let { id -> places.firstOrNull { it.id == id } },
                    snackbarHostState = snackbarHostState,
                    onBack = {
                        showMap = false
                        editingId = null
                    },
                    onSaved = { place, isNew ->
                        val status = MockLocationState.status.value
                        val coordinatesChanged = status.placeId == place.id &&
                            status.isRunning &&
                            (status.latitude != place.latitude || status.longitude != place.longitude)
                        showMap = false
                        editingId = null
                        val prefix = if (isNew) "已保存「${place.name}」" else "已更新「${place.name}」"
                        val suffix = if (coordinatesChanged) "，重新开始模拟后生效" else ""
                        scope.launch { snackbarHostState.showSnackbar(prefix + suffix) }
                    },
                )

                ROUTE_SETTINGS -> SettingsScreen(
                    snackbarHostState = snackbarHostState,
                    onBack = { showSettings = false },
                )

                else -> HomeScreen(
                    places = places,
                    snackbarHostState = snackbarHostState,
                    onAddPlace = {
                        editingId = null
                        showMap = true
                    },
                    onEditPlace = { place ->
                        editingId = place.id
                        showMap = true
                    },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}
