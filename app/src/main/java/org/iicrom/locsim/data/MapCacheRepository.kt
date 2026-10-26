package org.iicrom.locsim.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos

data class OfflineMapPackage(
    val file: File,
    val centerLatitude: Double?,
    val centerLongitude: Double?,
    val radiusKm: Int?,
    val maxZoom: Int?,
    val downloadedAt: Long,
    val sizeBytes: Long,
)

data class MapCacheStats(
    val onlineTileCount: Int = 0,
    val onlineBytes: Long = 0,
    val offlinePackages: List<OfflineMapPackage> = emptyList(),
    val offlineBytes: Long = 0,
)

data class OfflineDownloadRequest(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusKm: Int,
    val maxZoom: Int,
    val estimatedTiles: Int,
)

object OfflineMapUtils {
    const val MIN_ZOOM = 10
    const val MAX_TILES = 3000
    const val BYTES_PER_TILE_ESTIMATE = 32_000L

    val radiusOptions = listOf(2, 5, 10)
    val maxZoomOptions = listOf(14, 15, 16, 17)

    fun bounds(latitude: Double, longitude: Double, radiusKm: Int): BoundingBox {
        val latDelta = radiusKm * 1000.0 / 111_320.0
        val cosLatitude = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val lngDelta = radiusKm * 1000.0 / (111_320.0 * cosLatitude)
        return BoundingBox(
            (latitude + latDelta).coerceAtMost(85.0),
            (longitude + lngDelta).coerceAtMost(180.0),
            (latitude - latDelta).coerceAtLeast(-85.0),
            (longitude - lngDelta).coerceAtLeast(-180.0),
        )
    }

    fun estimatedTiles(latitude: Double, longitude: Double, radiusKm: Int, maxZoom: Int): Int =
        CacheManager.getTilesCoverage(bounds(latitude, longitude, radiusKm), MIN_ZOOM, maxZoom).size

    fun estimatedBytes(tiles: Int): Long = tiles * BYTES_PER_TILE_ESTIMATE
}

/**
 * 管理 osmdroid 的在线瓦片缓存，以及放在 osmdroid basePath 中的离线地图包。
 *
 * 离线包使用 osmdroid 的 SQLite 归档格式，扩展名为 .sqlite；MapView 创建时会自动扫描
 * basePath 下的 .sqlite 文件，并按 tile source 名称匹配其中的瓦片。
 */
class MapCacheRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    fun onlineCacheDir(): File {
        val dir = Configuration.getInstance().osmdroidTileCache
        dir.mkdirs()
        return dir
    }

    fun offlinePackageDir(): File {
        val dir = Configuration.getInstance().osmdroidBasePath
        dir.mkdirs()
        return dir
    }

    fun stats(): MapCacheStats {
        val online = onlineCacheStats()
        val packages = offlinePackages()
        return MapCacheStats(
            onlineTileCount = online.first,
            onlineBytes = online.second,
            offlinePackages = packages,
            offlineBytes = packages.sumOf { it.sizeBytes },
        )
    }

    fun offlinePackages(): List<OfflineMapPackage> {
        val dir = offlinePackageDir()
        val parsed = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PACKAGE_PREFIX) && it.extension == "sqlite" }
            ?.map(::toPackage)
            ?.sortedByDescending { it.downloadedAt }
            .orEmpty()
        return parsed
    }

    fun deletePackage(file: File): Boolean {
        if (!file.name.startsWith(PACKAGE_PREFIX) || file.extension != "sqlite") return false
        if (!file.absolutePath.startsWith(offlinePackageDir().absolutePath + File.separator)) return false
        return file.delete()
    }

    fun newTempPackageFile(): File =
        File(offlinePackageDir(), "$TEMP_PREFIX${UUID.randomUUID()}.tmp")

    fun finalizePackage(
        tempFile: File,
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        maxZoom: Int,
    ): OfflineMapPackage {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val baseName = String.format(
            Locale.US,
            "%s%.4f_%.4f_r%d_z%d_%s",
            PACKAGE_PREFIX,
            latitude,
            longitude,
            radiusKm,
            maxZoom,
            timestamp,
        )
        var target = File(offlinePackageDir(), "$baseName.sqlite")
        if (target.exists()) {
            target = File(offlinePackageDir(), "${baseName}_${System.currentTimeMillis() % 1000}.sqlite")
        }
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        return toPackage(target)
    }

    /**
     * 清理上次异常退出留下的临时包。默认只删除 24 小时前的文件，避免影响正在进行的下载。
     */
    fun cleanupTempFiles(olderThanMillis: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        offlinePackageDir().listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(TEMP_PREFIX) &&
                    it.name.endsWith(".tmp") &&
                    it.lastModified() < cutoff
            }
            ?.forEach { it.delete() }
    }

    /**
     * 清理在线瓦片缓存。调用前应确保地图页已销毁，避免与 MapView 的数据库连接冲突。
     */
    fun clearOnlineCache(): Boolean {
        var ok = true
        runCatching {
            val writer = SqlTileWriter()
            ok = writer.purgeCache() || ok
            writer.refreshDb()
        }.onFailure { Log.w(TAG, "purge online tile cache failed", it) }

        val dir = onlineCacheDir()
        dir.listFiles()?.forEach { file ->
            if (!file.deleteRecursively()) ok = false
        }
        dir.mkdirs()
        return ok
    }

    private fun onlineCacheStats(): Pair<Int, Long> {
        val dbFile = File(onlineCacheDir(), "cache.db")
        if (!dbFile.exists()) return 0 to 0
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            database.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(length(tile)), 0) FROM tiles",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getLong(1) else 0 to 0
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read online tile cache stats failed", t)
            0 to dbFile.length()
        } finally {
            runCatching { database?.close() }
        }
    }

    private fun toPackage(file: File): OfflineMapPackage {
        val match = PACKAGE_PATTERN.matchEntire(file.name)
        val latitude = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val longitude = match?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        val radius = match?.groupValues?.getOrNull(3)?.toIntOrNull()
        val maxZoom = match?.groupValues?.getOrNull(4)?.toIntOrNull()
        val timestamp = match?.groupValues?.getOrNull(5)?.let {
            runCatching {
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(it)?.time
            }.getOrNull()
        }
        return OfflineMapPackage(
            file = file,
            centerLatitude = latitude,
            centerLongitude = longitude,
            radiusKm = radius,
            maxZoom = maxZoom,
            downloadedAt = timestamp ?: file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    companion object {
        private const val TAG = "MapCacheRepository"
        private const val PACKAGE_PREFIX = "locsim_"
        private const val TEMP_PREFIX = "locsim-tmp-"
        private val PACKAGE_PATTERN = Regex(
            """^locsim_(-?\d+(?:\.\d+)?)_(-?\d+(?:\.\d+)?)_r(\d+)_z(\d+)_(\d{8}-\d{6})(?:_\d+)?\.sqlite$"""
        )

        @Volatile
        private var instance: MapCacheRepository? = null

        fun get(context: Context): MapCacheRepository =
            instance ?: synchronized(this) {
                instance ?: MapCacheRepository(context.applicationContext).also { instance = it }
            }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB")
            var value = bytes.toDouble() / 1024.0
            var index = 0
            while (value >= 1024.0 && index < units.lastIndex) {
                value /= 1024.0
                index++
            }
            return String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
