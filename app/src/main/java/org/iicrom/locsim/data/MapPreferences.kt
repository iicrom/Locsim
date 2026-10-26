package org.iicrom.locsim.data

import android.content.Context
import androidx.core.content.edit
import org.iicrom.locsim.util.Geo
import org.osmdroid.util.GeoPoint

/** 保存地图页上次停留的位置，下次“添加地点”时从那里继续。 */
class MapPreferences(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastCenter(): GeoPoint = GeoPoint(
        Double.fromBits(preferences.getLong(KEY_LATITUDE, Geo.DEFAULT_LATITUDE.toRawBits())),
        Double.fromBits(preferences.getLong(KEY_LONGITUDE, Geo.DEFAULT_LONGITUDE.toRawBits())),
    )

    fun saveCenter(latitude: Double, longitude: Double) {
        if (!Geo.isValid(latitude, longitude)) return
        preferences.edit {
            putLong(KEY_LATITUDE, latitude.toRawBits())
            putLong(KEY_LONGITUDE, longitude.toRawBits())
        }
    }

    fun saveCenter(point: GeoPoint) = saveCenter(point.latitude, point.longitude)

    private companion object {
        const val PREFS_NAME = "locsim_map"
        const val KEY_LATITUDE = "last_latitude"
        const val KEY_LONGITUDE = "last_longitude"
    }
}
