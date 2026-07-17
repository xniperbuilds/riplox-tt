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
 * Background-download guard — Infinix/Tecno (XOS "Hiber") jaise phones app process ko
 * background jaate hi FREEZE kar dete hain, foreground-service ke BAWAJOOD. Isi se
 * Riplox pe 3 masle aaye the: share → download start nahi hota, app kholo to start,
 * app band karo to pause. Ilaj = 3-cheez setup (user ek dafa karta hai):
 *   1. Battery optimization exemption (system dialog)
 *   2. Auto-start permission (OEM security app me)
 *   3. Recents me app-card LOCK (swipe-kill se bachao)
 * Ye helpers wo setup check + khulwate hain. Home banner (MainActivity) tab tak
 * dikhta hai jab tak (1) OFF hai.
 */
object BgGuard {

    /** Battery-optimization se exempt hai? (Instant/background downloads iske bina XOS pe freeze hoti) */
    fun batteryExempt(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ctx.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** System ka "Allow app to run in background?" dialog kholo. */
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
        // Kuch OEMs pe dialog nahi hota — seedha battery-settings list khol do
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

    /** OEM auto-start / startup-manager screens — jo pehla mile khol do.
     * Transsion (Infinix XOS / Tecno HiOS / itel) sab se pehle — test phone yehi.
     * App public hai is liye baqi bade OEMs bhi cover (Xiaomi/Oppo/Vivo/OnePlus/Huawei). */
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
