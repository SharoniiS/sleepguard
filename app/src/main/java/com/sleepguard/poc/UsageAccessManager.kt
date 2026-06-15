package com.sleepguard.poc

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Checks whether Usage Access has been granted, and opens the system settings
 * screen where the user can grant it.
 *
 * Usage Access is a special permission: it cannot be requested as a normal runtime
 * permission. The app declares PACKAGE_USAGE_STATS in the manifest, then the user
 * must toggle it on manually under Settings > Apps > Special app access > Usage access.
 */
class UsageAccessManager(private val context: Context) {

    /** Returns true if the user has granted Usage Access to this app. */
    @Suppress("DEPRECATION") // checkOpNoThrow works on API 28 (minSdk); unsafeCheckOpNoThrow is API 29+.
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens the system Usage Access settings screen.
     *
     * @return true if a settings screen was launched, false if no activity could
     *         handle the intent (rare, but possible on heavily customized ROMs).
     */
    fun openUsageAccessSettings(activity: Activity): Boolean {
        // First try the dedicated Usage Access settings screen.
        val usageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (usageIntent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(usageIntent)
            return true
        }

        // Fallback: open the general app settings so the user can find it manually.
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", activity.packageName, null)
        }
        if (appSettings.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(appSettings)
            return true
        }

        return false
    }
}
