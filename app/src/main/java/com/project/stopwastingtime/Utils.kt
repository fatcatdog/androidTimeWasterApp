// In a new Utils.kt file or at the top level of another file
package com.project.stopwastingtime
import java.util.Calendar

fun getStartOfDayInMillis(): Long {
    val calendar = Calendar.getInstance()
    // Check if current time is before 8 AM
    if (calendar.get(Calendar.HOUR_OF_DAY) < 8) {
        // If so, the period started at 8 AM yesterday
        calendar.add(Calendar.DAY_OF_YEAR, -1)
    }
    // Set the time to 8:00:00 AM for the correct day
    calendar.set(Calendar.HOUR_OF_DAY, 8)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
    