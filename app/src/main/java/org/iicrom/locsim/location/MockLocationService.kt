package org.iicrom.locsim.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.iicrom.locsim.MainActivity
import org.iicrom.locsim.R
import org.iicrom.locsim.data.SavedPlace
import org.iicrom.locsim.util.Geo

/**
 * 通过 LocationManager 的测试 Provider 持续写入定点位置。
 *
 * 与 GoGoGo 的做法一致：同时模拟 GPS 与 NETWORK 两个 Provider，避免只改 GPS 时
 * 部分应用仍从网络定位拿到真实位置。服务通过前台通知保持运行。
 */
class MockLocationService : Service() {

    private val handlerThread = HandlerThread("locsim-mock-location").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private lateinit var locationManager: LocationManager
    private var currentPlace: SavedPlace? = null
    private var providersReady = false
    private var foregroundStarted = false
    private var failure: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val place = currentPlace ?: return
            if (!providersReady) return
            try {
                pushLocation(LocationManager.GPS_PROVIDER, place, gps = true)
                runCatching { pushLocation(LocationManager.NETWORK_PROVIDER, place, gps = false) }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            } catch (_: SecurityException) {
                failAndStop("写入模拟位置被系统拒绝，请确认 Locsim 仍是模拟位置信息应用")
            } catch (t: Exception) {
                failAndStop("写入模拟位置失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val place = intent.toPlace() ?: run {
            startForegroundSafely(placeholderNotification())
            failAndStop("地点数据无效，请重新选择地点")
            return START_NOT_STICKY
        }

        currentPlace = place
        if (!startForegroundSafely(buildNotification(place))) {
            failAndStop("无法启动前台位置服务，请检查定位权限")
            return START_NOT_STICKY
        }

        if (!providersReady && !setupProviders()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 先写一次，确保目标应用马上能读到新位置。
        try {
            pushLocation(LocationManager.GPS_PROVIDER, place, gps = true)
            runCatching { pushLocation(LocationManager.NETWORK_PROVIDER, place, gps = false) }
        } catch (_: SecurityException) {
            failAndStop("写入模拟位置被系统拒绝，请确认 Locsim 仍是模拟位置信息应用")
            return START_NOT_STICKY
        } catch (t: Exception) {
            failAndStop("写入模拟位置失败：${t.message ?: t.javaClass.simpleName}")
            return START_NOT_STICKY
        }

        MockLocationState.markRunning(place)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, UPDATE_INTERVAL_MS)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        providersReady = false
        removeTestProvider(LocationManager.GPS_PROVIDER)
        removeTestProvider(LocationManager.NETWORK_PROVIDER)
        handlerThread.quitSafely()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        val message = failure
        if (message != null) {
            MockLocationState.fail(message)
        } else {
            MockLocationState.stopped()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            foregroundStarted = true
            true
        } catch (t: Exception) {
            failure = "无法启动前台位置服务：${t.message ?: t.javaClass.simpleName}"
            MockLocationState.fail(failure!!)
            false
        }
    }

    private fun setupProviders(): Boolean {
        if (!locationManager.isLocationEnabled) {
            fail("系统定位服务未开启，请先打开定位开关")
            return false
        }
        return try {
            removeTestProvider(LocationManager.GPS_PROVIDER)
            removeTestProvider(LocationManager.NETWORK_PROVIDER)

            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,
                true,
                false,
                false,
                true,
                true,
                true,
                ProviderProperties.POWER_USAGE_HIGH,
                ProviderProperties.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

            // 网络 Provider 是锦上添花，某些设备/ROM 不支持时仅使用 GPS。
            runCatching {
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER,
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_COARSE,
                )
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            }

            providersReady = true
            true
        } catch (_: SecurityException) {
            fail("无法创建模拟位置源：请将 Locsim 设为“模拟位置信息应用”")
            false
        } catch (t: Exception) {
            fail("无法创建模拟位置源：${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    private fun removeTestProvider(provider: String) {
        runCatching { locationManager.removeTestProvider(provider) }
    }

    private fun pushLocation(provider: String, place: SavedPlace, gps: Boolean) {
        val location = Location(provider).apply {
            latitude = place.latitude
            longitude = place.longitude
            altitude = place.altitude
            accuracy = if (gps) GPS_ACCURACY_METERS else NETWORK_ACCURACY_METERS
            verticalAccuracyMeters = if (gps) 5f else 15f
            speed = 0f
            speedAccuracyMetersPerSecond = 0.5f
            bearing = 0f
            bearingAccuracyDegrees = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (gps) {
                extras = Bundle().apply { putInt("satellites", 9) }
            }
        }
        locationManager.setTestProviderLocation(provider, location)
    }

    private fun failAndStop(message: String) {
        fail(message)
        stopSelf()
    }

    private fun fail(message: String) {
        failure = message
        MockLocationState.fail(message)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun placeholderNotification(): Notification = buildNotification(
        SavedPlace(
            id = "placeholder",
            name = "位置模拟",
            address = "",
            latitude = 0.0,
            longitude = 0.0,
        )
    )

    private fun buildNotification(place: SavedPlace): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("${place.name} · ${Geo.formatLatLng(place.latitude, place.longitude)}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun Intent.toPlace(): SavedPlace? {
        val id = getStringExtra(EXTRA_PLACE_ID) ?: return null
        val latitude = getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val longitude = getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        if (!Geo.isValid(latitude, longitude)) return null
        return SavedPlace(
            id = id,
            name = getStringExtra(EXTRA_PLACE_NAME).orEmpty().ifBlank { "我的地点" },
            address = "",
            latitude = latitude,
            longitude = longitude,
            altitude = getDoubleExtra(EXTRA_ALTITUDE, SavedPlace.DEFAULT_ALTITUDE),
        )
    }

    companion object {
        const val ACTION_START = "org.iicrom.locsim.action.START_MOCK"
        const val ACTION_STOP = "org.iicrom.locsim.action.STOP_MOCK"

        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ALTITUDE = "altitude"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val GPS_ACCURACY_METERS = 3f
        private const val NETWORK_ACCURACY_METERS = 40f
    }
}
