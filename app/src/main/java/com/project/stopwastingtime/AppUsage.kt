package com.project.stopwastingtime

import android.graphics.drawable.Drawable

/**
 * A data class to hold information about a single app's usage.
 * Like a simple data object or POJO in Java.
 */
// --- AppUsage Data Class (Modified) ---
data class AppUsage(
    val appName: String,
    val packageName: String, // Added for unique identification
    val usageTime: String,
    val appIcon: Drawable,
    val totalTimeInMillis: Long
)
