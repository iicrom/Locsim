package org.iicrom.locsim.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.iicrom.locsim.data.PlaceRepository
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.location.MockLocationController
import org.iicrom.locsim.location.MockLocationSettings
import org.iicrom.locsim.location.MockLocationState
import org.iicrom.locsim.location.MockLocationStatus

data class HomeAccess(
    val hasLocationPermission: Boolean = false,
    val locationServicesEnabled: Boolean = false,
    val mockAppSelected: Boolean = false,
    val checking: Boolean = true,
) {
    val ready: Boolean
        get() = !checking && hasLocationPermission && locationServicesEnabled && mockAppSelected
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlaceRepository.get(application)

    val places = repository.places
    val mockStatus: StateFlow<MockLocationStatus> = MockLocationState.status

    private val _access = MutableStateFlow(HomeAccess())
    val access: StateFlow<HomeAccess> = _access.asStateFlow()

    private var refreshJob: Job? = null
    private val accessMutex = Mutex()

    init {
        refreshAccess()
    }

    fun refreshAccess() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
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
                _access.value = HomeAccess(
                    hasLocationPermission = hasPermission,
                    locationServicesEnabled = locationEnabled,
                    mockAppSelected = mockSelected,
                    checking = false,
                )
            }
        }
    }

    fun startMock(place: SavedPlace) {
        val app = getApplication<Application>()
        if (!MockLocationSettings.hasLocationPermission(app)) {
            MockLocationState.fail("缺少定位权限，请先授予定位权限")
            return
        }
        if (!MockLocationSettings.isLocationServicesEnabled(app)) {
            MockLocationState.fail("系统定位服务未开启，请先打开定位开关")
            return
        }
        MockLocationController.start(app, place)
    }

    fun stopMock() {
        MockLocationController.stop(getApplication())
    }

    fun rename(place: SavedPlace, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == place.name) return
        val updated = place.copy(name = name, updatedAt = System.currentTimeMillis())
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsert(updated)
            MockLocationState.renameActive(place.id, name)
        }
    }

    fun delete(place: SavedPlace) {
        val status = MockLocationState.status.value
        if (status.placeId == place.id && (status.isRunning || status.isStarting)) {
            stopMock()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(place.id)
        }
    }

    fun clearError() {
        MockLocationState.clearError()
    }
}
