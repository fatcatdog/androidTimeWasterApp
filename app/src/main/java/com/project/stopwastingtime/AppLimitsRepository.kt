package com.project.stopwastingtime

import android.content.Context
import androidx.compose.ui.input.key.type
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages saving and loading app limits using SharedPreferences.
 * This acts as our simple database.
 */
class AppLimitsRepository(context: Context) {

    // SharedPreferences is a simple way to store key-value pairs persistently.
    private val prefs = context.getSharedPreferences("app_limits", Context.MODE_PRIVATE)
    private val gson = Gson() // For converting our list to a storable string (JSON)

    companion object {
        private const val KEY_APP_LIMITS = "key_app_limits"
    }

    /**
     * Saves a list of all app limits to SharedPreferences.
     */
    fun saveLimits(limits: List<AppLimit>) {
        val json = gson.toJson(limits) // Convert the list to a JSON string
        prefs.edit().putString(KEY_APP_LIMITS, json).apply()
    }

    /**
     * Retrieves the list of all app limits from SharedPreferences.
     */
    fun getLimits(): List<AppLimit> {
        val json = prefs.getString(KEY_APP_LIMITS, null)
        return if (json != null) {
            // Convert the JSON string back into a List<AppLimit>
            val type = object : TypeToken<List<AppLimit>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList() // Return an empty list if nothing is saved yet
        }
    }
}
