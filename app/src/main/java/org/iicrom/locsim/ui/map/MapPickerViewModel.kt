package org.iicrom.locsim.ui.map

import android.app.Application
import android.location.Geocoder
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iicrom.locsim.data.MapPreferences
import org.iicrom.locsim.data.PlaceRepository
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.location.MockLocationSettings
import org.iicrom.locsim.location.MockLocationState
import org.iicrom.locsim.util.Geo
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.UUID

data class GeoPlace(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

class MapPickerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlaceRepository.get(application)
    private val mapPreferences = MapPreferences(application)

    fun initialCenter(place: SavedPlace?): GeoPoint = place?.let {
        GeoPoint(it.latitude, it.longitude)
    } ?: mapPreferences.lastCenter()

    fun rememberCenter(latitude: Double, longitude: Double) {
        mapPreferences.saveCenter(latitude, longitude)
    }

    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext null
                val geocoder = Geocoder(getApplication(), Locale.getDefault())
                val results = geocoder.getFromLocation(latitude, longitude, 1) ?: return@withContext null
                results.firstOrNull()?.toDisplayAddress()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    @Suppress("DEPRECATION")
    suspend fun search(query: String): Result<List<GeoPlace>> = withContext(Dispatchers.IO) {
        try {
            val trimmed = query.trim()
            Geo.parseCoordinates(trimmed)?.let { (latitude, longitude) ->
                return@withContext Result.success(
                    listOf(
                        GeoPlace(
                            name = "坐标点",
                            address = "手动输入的 WGS-84 坐标",
                            latitude = latitude,
                            longitude = longitude,
                        )
                    )
                )
            }
            if (!Geocoder.isPresent()) {
                return@withContext Result.failure(
                    IllegalStateException("系统地理编码服务不可用，请改用坐标输入")
                )
            }
            val geocoder = Geocoder(getApplication(), Locale.getDefault())
            val addresses = geocoder.getFromLocationName(trimmed, 8).orEmpty()
            val places = addresses.mapNotNull { address ->
                val latitude = address.latitude
                val longitude = address.longitude
                if (!Geo.isValid(latitude, longitude)) return@mapNotNull null
                val display = address.toDisplayAddress()
                val title = address.featureName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: display.substringBefore(',').trim().takeIf { it.isNotEmpty() }
                    ?: "未知地点"
                GeoPlace(
                    name = title,
                    address = if (display == title) "" else display,
                    latitude = latitude,
                    longitude = longitude,
                )
            }.distinctBy {
                String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude)
            }
            Result.success(places)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePlace(
        existingId: String?,
        name: String,
        address: String,
        latitude: Double,
        longitude: Double,
    ): SavedPlace = withContext(Dispatchers.IO) {
        val existing = existingId?.let { id ->
            repository.places.value.firstOrNull { it.id == id }
        }
        val now = System.currentTimeMillis()
        val place = existing?.copy(
            name = name.trim(),
            address = address.trim(),
            latitude = latitude,
            longitude = longitude,
            updatedAt = now,
        ) ?: SavedPlace(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            address = address.trim(),
            latitude = latitude,
            longitude = longitude,
            createdAt = now,
            updatedAt = now,
        )
        repository.upsert(place)
        place
    }

    @Suppress("DEPRECATION")
    fun lastKnownLocation(): GeoPoint? {
        val status = MockLocationState.status.value
        if (status.isRunning) {
            return GeoPoint(status.latitude, status.longitude)
        }
        val app = getApplication<Application>()
        if (!MockLocationSettings.hasLocationPermission(app)) return null
        val manager = app.getSystemService(LocationManager::class.java) ?: return null
        if (!manager.isLocationEnabled) return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time in 0..MAX_LOCATION_AGE_MS }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }
}

private const val MAX_LOCATION_AGE_MS = 15 * 60 * 1000L

private fun android.location.Address.toDisplayAddress(): String {
    getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return listOfNotNull(featureName, locality, subAdminArea, adminArea, countryName)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")
}
