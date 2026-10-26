package org.iicrom.locsim.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.iicrom.locsim.data.SavedPlace

/** 当前模拟会话的界面状态。服务与界面运行在同一进程，因此用单例 StateFlow 同步。 */
data class MockLocationStatus(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val placeId: String? = null,
    val placeName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val error: String? = null,
) {
    val hasTarget: Boolean get() = placeId != null
}

object MockLocationState {

    private val _status = MutableStateFlow(MockLocationStatus())
    val status: StateFlow<MockLocationStatus> = _status.asStateFlow()

    fun markStarting(place: SavedPlace) {
        _status.value = MockLocationStatus(
            isStarting = true,
            placeId = place.id,
            placeName = place.name,
            latitude = place.latitude,
            longitude = place.longitude,
        )
    }

    fun markRunning(place: SavedPlace) {
        _status.value = MockLocationStatus(
            isRunning = true,
            placeId = place.id,
            placeName = place.name,
            latitude = place.latitude,
            longitude = place.longitude,
        )
    }

    fun stopped() {
        _status.value = MockLocationStatus()
    }

    fun fail(message: String) {
        _status.update {
            it.copy(isRunning = false, isStarting = false, error = message)
        }
    }

    fun clearError() {
        _status.update { it.copy(error = null) }
    }

    fun renameActive(placeId: String, name: String) {
        _status.update {
            if (it.placeId == placeId) it.copy(placeName = name) else it
        }
    }
}
