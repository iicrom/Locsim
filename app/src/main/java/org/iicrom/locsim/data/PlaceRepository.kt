package org.iicrom.locsim.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.iicrom.locsim.util.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 轻量级地点仓库：使用 SharedPreferences 保存 JSON，进程内通过 StateFlow 通知界面。
 *
 * 数据量只有几十到几百个地点，不需要引入数据库；所有写操作都在调用线程完成，
 * 界面侧通过 Dispatchers.IO 调用即可。
 */
class PlaceRepository private constructor(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _places = MutableStateFlow(readPlaces())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    @Synchronized
    fun upsert(place: SavedPlace): SavedPlace {
        val current = _places.value
        val index = current.indexOfFirst { it.id == place.id }
        val next = if (index >= 0) {
            current.toMutableList().also { it[index] = place }
        } else {
            listOf(place) + current
        }
        _places.value = next
        persist(next)
        return place
    }

    @Synchronized
    fun delete(id: String) {
        val next = _places.value.filterNot { it.id == id }
        if (next.size == _places.value.size) return
        _places.value = next
        persist(next)
    }

    private fun readPlaces(): List<SavedPlace> {
        val raw = preferences.getString(KEY_PLACES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val latitude = item.optDouble("latitude", Double.NaN)
                    val longitude = item.optDouble("longitude", Double.NaN)
                    if (!Geo.isValid(latitude, longitude)) continue
                    val createdAt = item.optLong("createdAt", 0L).takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    add(
                        SavedPlace(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { "我的地点" },
                            address = item.optString("address"),
                            latitude = latitude,
                            longitude = longitude,
                            altitude = item.optDouble("altitude", SavedPlace.DEFAULT_ALTITUDE),
                            createdAt = createdAt,
                            updatedAt = item.optLong("updatedAt", createdAt),
                        )
                    )
                }
            }
                .sortedByDescending { it.createdAt }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun persist(places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject().apply {
                    put("id", place.id)
                    put("name", place.name)
                    put("address", place.address)
                    put("latitude", place.latitude)
                    put("longitude", place.longitude)
                    put("altitude", place.altitude)
                    put("createdAt", place.createdAt)
                    put("updatedAt", place.updatedAt)
                }
            )
        }
        preferences.edit { putString(KEY_PLACES, array.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "locsim_places"
        private const val KEY_PLACES = "places_v1"

        @Volatile
        private var instance: PlaceRepository? = null

        fun get(context: Context): PlaceRepository =
            instance ?: synchronized(this) {
                instance ?: PlaceRepository(context.applicationContext).also { instance = it }
            }
    }
}
