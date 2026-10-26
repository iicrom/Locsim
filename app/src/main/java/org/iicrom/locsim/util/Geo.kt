package org.iicrom.locsim.util

import java.util.Locale

/**
 * 地图与模拟定位统一使用 WGS-84 十进制度坐标（OpenStreetMap 与 Android 模拟位置接口一致）。
 */
object Geo {
    /** 未保存过地图位置时的默认中心：北京天安门。 */
    const val DEFAULT_LATITUDE = 39.9087
    const val DEFAULT_LONGITUDE = 116.3975

    fun isValidLatitude(value: Double): Boolean =
        value.isFinite() && value >= -90.0 && value <= 90.0

    fun isValidLongitude(value: Double): Boolean =
        value.isFinite() && value >= -180.0 && value <= 180.0

    fun isValid(latitude: Double, longitude: Double): Boolean =
        isValidLatitude(latitude) && isValidLongitude(longitude)

    fun formatLatLng(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    fun formatLatitude(latitude: Double): String =
        String.format(Locale.US, "%.6f", latitude)

    fun formatLongitude(longitude: Double): String =
        String.format(Locale.US, "%.6f", longitude)

    /**
     * 解析 “39.9087, 116.3975”“39.9087 116.3975” 等输入，失败返回 null。
     * 顺序为纬度在前、经度在后。
     */
    fun parseCoordinates(input: String): Pair<Double, Double>? {
        val parts = input
            .replace('，', ' ')
            .replace(',', ' ')
            .replace(';', ' ')
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (!isValid(latitude, longitude)) return null
        return latitude to longitude
    }

    /** 用地址的第一段作为保存地点的默认名称。 */
    fun suggestedName(address: String): String {
        val first = address
            .split(',', '，')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return first.take(24).ifBlank { "我的地点" }
    }
}
