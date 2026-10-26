package org.iicrom.locsim

import android.app.Application
import org.iicrom.locsim.data.MapCacheRepository
import org.osmdroid.config.Configuration
import java.io.File

class LocsimApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
        // 新进程启动时不可能有下载任务，可以清理上次异常退出留下的临时离线包。
        runCatching { MapCacheRepository.get(this).cleanupTempFiles(olderThanMillis = 0L) }
    }

    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        // OSM 瓦片服务要求可识别的 User-Agent。
        config.userAgentValue = "Locsim/1.0 (org.iicrom.locsim)"
        config.osmdroidBasePath = File(filesDir, "osmdroid")
        config.osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        config.tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
    }
}
