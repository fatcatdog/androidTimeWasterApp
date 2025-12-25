package com.project.stopwastingtime

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import android.content.pm.ServiceInfo

class UsageMonitorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = AppLimitsRepository(context)
    private val blockedApps = mutableSetOf<String>()

    // --- ADD THESE NEW PROPERTIES ---
    private var currentAppSessionStart: Long = 0L
    private var lastTrackedPackage: String? = null
// --- END OF ADDITION ---

    companion object {
        const val WORK_NAME = "UsageMonitorWorker"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "UsageMonitorChannel"
        private const val TAG = "UsageMonitorWorker"

        fun getStartTimeForDailyReset(): Long {
            val calendar = Calendar.getInstance()
            val now = calendar.timeInMillis

            // Set calendar to 5 AM today
            calendar.set(Calendar.HOUR_OF_DAY, 5)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            // If it's already past 5 AM today, the start time is 5 AM today.
            if (now >= calendar.timeInMillis) {
                return calendar.timeInMillis
            } else {
                // Otherwise, the start time was 5 AM yesterday.
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                return calendar.timeInMillis
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "UsageMonitorWorker starting.")
        try {
            // This is required to run as a long-running worker.
            val foregroundInfo = createForegroundInfo()
            setForeground(foregroundInfo)

            // The main monitoring loop, moved from the service.
            while (coroutineContext.isActive) {
                checkForegroundApp()
                delay(3000) // Check every 3 seconds
            }
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {            Log.e(TAG, "Foreground service start not allowed.", e)
            // This can happen on Android 12+ if background starts are restricted.
            // WorkManager should handle this, but if it fails, we return failure.
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Error in UsageMonitorWorker", e)
            return Result.retry() // Retry if something else goes wrong.
        }

        return Result.success() // This line is unlikely to be reached in a continuous loop.
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("App Monitor Active")
            .setContentText("Monitoring your app usage to help you stay focused.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use a real drawable
            .setOngoing(true)
            .build()

        // --- THIS IS THE FIX ---
        // On Android 14+ (SDK 34+), you MUST specify the foreground service type.
        // It must match a type declared in your AndroidManifest.xml.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE // Specify the type here
            )
        } else {
            // For older versions, use the constructor without the type.
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
        // --- END OF FIX ---
    }

    // --- All the helper functions are moved here from the service ---

    private suspend fun checkForegroundApp() {
        val foregroundAppPackage = getForegroundAppPackageName()

        // --- THIS IS THE NEW RESILIENT LOGIC ---
        // Only reset the session if we detect a NEW and DIFFERENT app.
        // Do NOT reset if the result is null.
        if (foregroundAppPackage != null && foregroundAppPackage != lastTrackedPackage) { // <-- FIX: Corrected the typo here
            // A different app has come to the foreground.
            lastTrackedPackage = foregroundAppPackage
            currentAppSessionStart = System.currentTimeMillis()
            Log.d(TAG, "New foreground app: $foregroundAppPackage. Resetting session timer.")
        } else if (foregroundAppPackage == null && lastTrackedPackage != null) {
            // If we get a null, it could be a blip. Log it but DON'T reset the timer.
            // We assume the last app is still active until proven otherwise.
            Log.d(TAG, "getForegroundAppPackageName returned null, but assuming $lastTrackedPackage is still active.")
        }
        // --- END OF NEW LOGIC ---

        // The rest of the logic can now rely on lastTrackedPackage
        val appToCheck = lastTrackedPackage ?: return // If we've never seen an app, do nothing.

        if (appToCheck in blockedApps) {
            returnToHomeScreen()
            return
        }

        val limits = repository.getLimits()
        val limit = limits.find { it.packageName == appToCheck }

        if (limit != null) {
            val usageTimeMinutes = getAppUsageTime(appToCheck)
            Log.d(TAG, "Checking app: $appToCheck, Usage: $usageTimeMinutes min, Limit: ${limit.limitMinutes} min")
            if (usageTimeMinutes >= limit.limitMinutes) {
                blockedApps.add(appToCheck)
                notifyAndBlockApp(appToCheck, usageTimeMinutes, limit.limitMinutes)
            }
        }
    }

    private suspend fun getAppUsageTime(packageName: String): Long = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = getStartTimeForDailyReset()

        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_BEST,
            startTime,
            endTime
        )

        val aggregatedTime = stats
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }

        // --- THIS IS THE FIX ---
        var activeSessionTime = 0L
        // If the app we're checking is the one currently being tracked, calculate its active, unrecorded time.
        if (packageName == lastTrackedPackage && currentAppSessionStart > 0) {
            activeSessionTime = System.currentTimeMillis() - currentAppSessionStart
        }

        // The total time is the historical time plus the current active session.
        val totalTimeInMillis = aggregatedTime + activeSessionTime
        // --- END OF FIX ---

        return@withContext (totalTimeInMillis / (1000 * 60)) // Return time in minutes
    }


    private suspend fun getForegroundAppPackageName(): String? = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (60 * 1000)

        // --- THIS IS THE NEW, MORE RELIABLE LOGIC ---
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        var lastApp: String? = null
        val event = android.app.usage.UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.packageName
            }
        }

        // Ignore results from the launcher to prevent session resets.
        if (lastApp != null && isLauncherPackage(lastApp)) {
            Log.d(TAG, "Ignoring launcher package: $lastApp")
            return@withContext null // Treat launcher as "no app in foreground"
        }

        Log.d(TAG, "Final foreground app determined: $lastApp")
        return@withContext lastApp
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            // --- THIS IS THE FIX ---
            // Use backticks to escape the 'package' keyword.
            `package` = packageName
            // --- END OF FIX ---
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo != null
    }


    private fun returnToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    private fun notifyAndBlockApp(packageName: String, usageTime: Long, limit: Long) {
        returnToHomeScreen()
        val appName = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) { "An app" }

        val notificationTitle = "Time Limit Reached for $appName"
        val notificationText = "Used for $usageTime minutes. Limit was $limit minutes."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use a real drawable
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(packageName.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Usage Monitor"
            val descriptionText = "Channel for the app usage monitor service"
            val importance = NotificationManager.IMPORTANCE_LOW // Use LOW to be less intrusive
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


}
