package com.example.livetranslate.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Battery exemption + OEM keep-alive helpers for long-running recording sessions.
 */
object KeepAliveHelper {
    private const val TAG = "KeepAliveHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog asking the user to exempt this app from battery optimization.
     * Falls back to the battery optimization list if the direct request is blocked.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "requestIgnoreBatteryOptimizations failed, open list", e)
            openBatteryOptimizationSettings(context)
        }
    }

    /** Opens the system list of apps with battery optimization settings. */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openBatteryOptimizationSettings failed", e)
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openAppDetailsSettings failed", e)
        }
    }

    /**
     * Best-effort jump into OEM auto-start / background-run pages
     * (Xiaomi, Huawei, OPPO, Vivo, Samsung, etc.).
     * @return true if at least one intent was launched
     */
    fun openOemAutoStartSettings(context: Context): Boolean {
        val candidates = oemKeepAliveIntents(context.packageName)
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "OEM intent failed: ${intent.component}", e)
            }
        }
        openAppDetailsSettings(context)
        return false
    }

    private fun oemKeepAliveIntents(packageName: String): List<Intent> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val list = mutableListOf<Intent>()

        // Xiaomi / Redmi / POCO
        list += Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        list += Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        list += Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            )
        ).apply {
            putExtra("package_name", packageName)
            putExtra("package_label", "Live Translate")
        }

        // Huawei / Honor
        list += Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )

        // OPPO / Realme / ColorOS
        list += Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
        )

        // Vivo
        list += Intent().setComponent(
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )

        // Samsung
        list += Intent().setComponent(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )
        list += Intent().setComponent(
            ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )

        // Meizu
        list += Intent().setComponent(
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.permission.SmartBGActivity"
            )
        )

        // OnePlus
        list += Intent().setComponent(
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        )

        // Prefer manufacturer-matching first for slightly better hit rate
        return if (manufacturer.isNotBlank()) {
            list.sortedByDescending { intent ->
                val pkg = intent.component?.packageName.orEmpty().lowercase()
                when {
                    manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                        pkg.contains("miui") || pkg.contains("xiaomi")
                    manufacturer.contains("huawei") -> pkg.contains("huawei")
                    manufacturer.contains("honor") -> pkg.contains("hihonor") || pkg.contains("huawei")
                    manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                        pkg.contains("coloros") || pkg.contains("oppo")
                    manufacturer.contains("vivo") -> pkg.contains("vivo") || pkg.contains("iqoo")
                    manufacturer.contains("samsung") -> pkg.contains("samsung")
                    manufacturer.contains("meizu") -> pkg.contains("meizu")
                    manufacturer.contains("oneplus") -> pkg.contains("oneplus")
                    else -> false
                }
            }
        } else {
            list
        }
    }

    fun newPartialWakeLock(context: Context, tag: String): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
        }
    }

    @Suppress("DEPRECATION")
    fun newWifiLock(context: Context, tag: String): WifiManager.WifiLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag).apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "createWifiLock failed", e)
            null
        }
    }

    fun safeAcquireWakeLock(lock: PowerManager.WakeLock?, timeoutMs: Long = 60 * 60 * 1000L) {
        if (lock == null) return
        try {
            if (!lock.isHeld) {
                // Timeout avoids permanent drain if stop path is missed
                lock.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "acquire wake lock failed", e)
        }
    }

    fun safeReleaseWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "release wake lock failed", e)
        }
    }

    fun safeAcquireWifiLock(lock: WifiManager.WifiLock?) {
        if (lock == null) return
        try {
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "acquire wifi lock failed", e)
        }
    }

    fun safeReleaseWifiLock(lock: WifiManager.WifiLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "release wifi lock failed", e)
        }
    }
}
