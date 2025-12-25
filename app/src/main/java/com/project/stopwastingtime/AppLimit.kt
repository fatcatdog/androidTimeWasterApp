package com.project.stopwastingtime

/**
 * A data class to hold the time limit for a single application.
 *
 * @param packageName The unique identifier of the app (e.g., "com.android.chrome").
 * @param limitMinutes The time limit set by the user, in minutes.
 */
data class AppLimit(
    val packageName: String,
    val limitMinutes: Long
)
