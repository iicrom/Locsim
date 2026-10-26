package org.iicrom.locsim.data

/** 一个保存在列表中的定点位置。坐标使用 WGS-84。 */
data class SavedPlace(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = DEFAULT_ALTITUDE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        const val DEFAULT_ALTITUDE = 55.0
    }
}
