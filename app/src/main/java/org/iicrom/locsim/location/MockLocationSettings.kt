package org.iicrom.locsim.location

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 首次使用需要的系统设置检查与跳转。 */
object MockLocationSettings {

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isLocationServicesEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return manager.isLocationEnabled
    }

    /**
     * 判断本应用是否被选为“模拟位置信息应用”。
     *
     * 大多数系统可直接通过 AppOps 判断；部分 ROM 的 appops 返回默认值，
     * 此时尝试临时注册测试 Provider 作为兜底（仅在服务未运行时调用）。
     */
    fun isMockLocationAppSelected(context: Context, allowProbe: Boolean = true): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = runCatching {
            appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        }.getOrNull()

        when (mode) {
            AppOpsManager.MODE_ALLOWED -> return true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> return false
        }
        return allowProbe && probeTestProvider(context)
    }

    @SuppressLint("WrongConstant")
    private fun probeTestProvider(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) return false
        return try {
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            manager.addTestProvider(
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
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun openDeveloperOptions(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
