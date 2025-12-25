package com.project.stopwastingtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.icu.util.Calendar
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.*
import java.util.concurrent.TimeUnit

class StopWastingTimeApp : Application() {

    companion object {
        const val LIMIT_REACHED_CHANNEL_ID = "limit_reached_channel"
        const val FOREGROUND_SERVICE_CHANNEL_ID = "foreground_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
//        scheduleUsageMonitoring()
    }

    // NEW: Function to schedule the monitoring worker
//    private fun scheduleUsageMonitoring() {
//        val constraints = Constraints.Builder()
//            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
//            .build()
//
//        // Create a periodic request to run every 15 minutes (the minimum allowed)
////        val monitoringRequest =
////            PeriodicWorkRequestBuilder<UsageMonitoringWorker>(15, TimeUnit.MINUTES)
////                .setConstraints(constraints)
////                .build()
//
//        // Enqueue the work as unique to prevent duplicates
////        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
////            "usage_monitoring_work",
////            ExistingPeriodicWorkPolicy.KEEP, // Keep the existing work if it's already scheduled
////            monitoringRequest
////        )
//    }

    private fun createNotificationChannel() {
        // Check if the device is on Android 8.0 (Oreo) or higher, as channels are not supported before.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // --- 1. Create the High-Importance Channel for Limit Alerts ---
            val limitAlertsChannelName = "App Limit Alerts"
            val limitAlertsDescription = "Notifications shown when an app's time limit is reached."
            val limitAlertsImportance = NotificationManager.IMPORTANCE_HIGH
            val limitAlertsChannel = NotificationChannel(
                LIMIT_REACHED_CHANNEL_ID,
                limitAlertsChannelName,
                limitAlertsImportance
            ).apply {
                description = limitAlertsDescription
            }

            // --- 2. Create the Low-Importance Channel for the Foreground Service ---
            val serviceChannelName = "App Usage Monitor"
            val serviceChannelDescription = "Persistent notification to keep the app monitor running."
            val serviceChannelImportance = NotificationManager.IMPORTANCE_LOW // Use LOW to be non-intrusive
            val serviceChannel = NotificationChannel(
                FOREGROUND_SERVICE_CHANNEL_ID,
                serviceChannelName,
                serviceChannelImportance
            ).apply {
                description = serviceChannelDescription
            }

            // --- Register both channels with the system ---
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(limitAlertsChannel)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

}