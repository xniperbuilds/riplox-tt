package com.xniperbuilds.downloader

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * ============================================================================
 * "A NEW VERSION IS OUT" — how the user finds out about an update
 * ============================================================================
 *
 * Only **Play's In-App Updates API** (Play Core). FLEXIBLE flow: the update downloads
 * quietly in the background while the user keeps using the app, then our card on the
 * home screen offers "Restart to finish" (`completeUpdate()`). If the update is
 * **urgent** (`updatePriority >= 4` in the Play Console, or the user's version is 7+
 * days stale) it switches to **IMMEDIATE** — Google's own full-screen flow that will
 * not let you continue without updating. (A release like v1.1.2, where the old build
 * simply does not work, is exactly what that is for.) An IMMEDIATE update left
 * half-finished resumes by itself on the next app open.
 *
 * ⚠️ This only runs on an app **installed from Play** — Play Core does nothing on a
 * sideload/GitHub build, and there is DELIBERATELY no home-grown update check there.
 * The app never downloads or installs an APK by itself, which also keeps it on the
 * right side of Play's Device & Network Abuse policy.
 *
 * ⚠️ This feature only helps users who ALREADY have a version containing this code.
 * Anyone still on an older (broken) build gets no prompt — for them, Play's own
 * auto-update is the only route.
 */

private const val UP_LOG = "RiploxTT"
private const val PLAY_STORE_PKG = "com.android.vending"
private const val REQ_UPDATE = 7301

/** Home pe dikhane wala update-notice (null = kuch nahi dikhana). */
data class UpdateNotice(
    val title: String,
    val body: String,
    val cta: String,
    val onClick: () -> Unit
)

/** App Play Store se install hui hai? (Play In-App Updates sirf usi surat me chalta hai.) */
fun installedFromPlay(context: Context): Boolean = try {
    val pm = context.packageManager
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        pm.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        pm.getInstallerPackageName(context.packageName)
    }
    installer == PLAY_STORE_PKG
} catch (e: Exception) {
    false
}

/**
 * Manager for the update check. MainActivity creates it, calls `check()`, and drops the
 * listener again in `dispose()`.
 */
class AppUpdates(private val activity: ComponentActivity) {

    private val mgr: AppUpdateManager? = try {
        AppUpdateManagerFactory.create(activity)
    } catch (e: Exception) {
        Log.w(UP_LOG, "app-update manager unavailable", e)
        null
    }

    private var listener: InstallStateUpdatedListener? = null
    private var notify: ((UpdateNotice?) -> Unit)? = null

    /** Run Play's update check and, if needed, start the flow. */
    fun check(onNotice: (UpdateNotice?) -> Unit) {
        notify = onNotice
        val m = mgr ?: return
        if (!installedFromPlay(activity)) return // the Play API only works on Play installs
        try {
            m.appUpdateInfo
                .addOnSuccessListener { info ->
                    when (info.updateAvailability()) {
                        UpdateAvailability.UPDATE_AVAILABLE -> {
                            // How urgent is it? The priority set in the Play Console, or how
                            // stale the user's version has become — if either one says
                            // "urgent", go full-screen IMMEDIATE.
                            val stale = info.clientVersionStalenessDays() ?: 0
                            val urgent = info.updatePriority() >= 4 || stale >= 7
                            if (urgent && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                                startFlow(info, AppUpdateType.IMMEDIATE)
                            } else if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                                registerFlexibleListener()
                                startFlow(info, AppUpdateType.FLEXIBLE)
                            }
                        }
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                            // An IMMEDIATE update was abandoned part-way — without this it
                            // would hang half-finished forever. Pick it back up.
                            startFlow(info, AppUpdateType.IMMEDIATE)
                        }
                        else -> {
                        }
                    }
                }
                .addOnFailureListener { e -> Log.i(UP_LOG, "no play update info: ${e.message}") }
        } catch (e: Exception) {
            Log.w(UP_LOG, "play update check failed", e)
        }
    }

    private fun startFlow(info: AppUpdateInfo, type: Int) {
        try {
            mgr?.startUpdateFlowForResult(
                info, activity, AppUpdateOptions.newBuilder(type).build(), REQ_UPDATE
            )
        } catch (e: Exception) {
            Log.w(UP_LOG, "startUpdateFlow failed", e)
        }
    }

    /** FLEXIBLE update download ho jaye to user ko "Restart to finish" dikhana HAMARA kaam hai. */
    private fun registerFlexibleListener() {
        if (listener != null) return
        val l = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                notify?.invoke(
                    UpdateNotice(
                        title = "✅ Update downloaded",
                        body = "Restart Riplox TT to finish installing the update.",
                        cta = "Restart now"
                    ) { try { mgr?.completeUpdate() } catch (_: Exception) {} }
                )
            }
        }
        listener = l
        try { mgr?.registerListener(l) } catch (_: Exception) {}
    }

    fun dispose() {
        listener?.let { l -> try { mgr?.unregisterListener(l) } catch (_: Exception) {} }
        listener = null
        notify = null
    }
}
