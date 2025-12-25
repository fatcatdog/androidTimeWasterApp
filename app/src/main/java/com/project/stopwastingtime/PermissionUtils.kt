package com.project.stopwastingtime

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.Manifest // Make sure this is imported
/**
 * Checks if the app has the permission to get usage stats.
 * This is required to monitor app usage.
 */
fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Checks if the app has the permission to display over other apps.
 * This is required to block apps by bringing the home screen to the front.
 */
fun hasOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        // Before Android M, this permission was granted at install time.
        true
    }
}

/**
 * Checks if the app has permission to post notifications.
 * This is only required on Android 13 (API 33) and above.
 */
fun hasNotificationPermission(context: Context): Boolean {
    // Before Android 13 (TIRAMISU), the permission is granted by default.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    // On Android 13 and later, we need to explicitly check.
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}
