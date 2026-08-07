package com.xniperbuilds.downloader

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Background-download guard — phones like Infinix/Tecno (XOS "Hiber") FREEZE the app process
 * the moment it goes to background, DESPITE a foreground service. That caused three distinct
 * complaints: share → the download never starts; open the app → it starts; close the app →
 * it pauses. The cure is a 3-part setup the user does once:
 *   1. Battery-optimization exemption (system dialog)
 *   2. Auto-start permission (inside the OEM's security app)
 *   3. LOCK the app card in Recents (protects against a swipe-kill)
 * These helpers check that setup and open the relevant screens. The home banner
 * (MainActivity) keeps showing while (1) is OFF.
 */
object BgGuard {

    /** Exempt from battery optimization? (Without it, instant/background downloads freeze on XOS.) */
    fun batteryExempt(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ctx.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Open the system "Allow app to run in background?" dialog. */
    @SuppressLint("BatteryLife")
    fun requestBatteryExempt(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        // Some OEMs have no such dialog — open the battery-settings list directly
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** OEM auto-start / startup-manager screens — open the first one that exists.
     * Transsion (Infinix XOS / Tecno HiOS / itel) comes first, as that is the test hardware.
     * The other major OEMs are covered too (Xiaomi/Oppo/Vivo/OnePlus/Huawei). */
    private val AUTO_START_SCREENS = listOf(
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        ComponentName("com.transsion.phonemanager", "com.itel.autostart.AutoStartActivity"),
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    )

    /** Auto-start manager kholo; koi OEM screen na mile to app-info page (wahan bhi hota hai). */
    fun openAutoStart(ctx: Context): Boolean {
        for (cn in AUTO_START_SCREENS) {
            try {
                ctx.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
