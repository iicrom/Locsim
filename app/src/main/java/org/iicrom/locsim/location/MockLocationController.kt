package org.iicrom.locsim.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.iicrom.locsim.data.SavedPlace

 object MockLocationController {

    /** 启动或更新模拟位置；返回 false 时错误已写入 [MockLocationState]。 */
    fun start(context: Context, place: SavedPlace): Boolean {
        MockLocationState.markStarting(place)
        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_PLACE_ID, place.id)
            putExtra(MockLocationService.EXTRA_PLACE_NAME, place.name)
            putExtra(MockLocationService.EXTRA_LATITUDE, place.latitude)
            putExtra(MockLocationService.EXTRA_LONGITUDE, place.longitude)
            putExtra(MockLocationService.EXTRA_ALTITUDE, place.altitude)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (t: Exception) {
            MockLocationState.fail("启动模拟服务失败：${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
        MockLocationState.stopped()
    }
}
