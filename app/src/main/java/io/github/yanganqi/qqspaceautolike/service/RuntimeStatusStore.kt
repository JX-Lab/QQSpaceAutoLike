package io.github.yanganqi.qqspaceautolike.service

import android.content.Context
import android.content.SharedPreferences

data class RuntimeStatus(
    val message: String?,
    val isRunning: Boolean,
)

class RuntimeStatusStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RuntimeStatus {
        val message = preferences.getString(KEY_MESSAGE, null)?.trim()?.takeIf { it.isNotEmpty() }
        return RuntimeStatus(
            message = message,
            isRunning = preferences.getBoolean(KEY_IS_RUNNING, false),
        )
    }

    fun setRunning(message: String) {
        preferences.edit()
            .putString(KEY_MESSAGE, message)
            .putBoolean(KEY_IS_RUNNING, true)
            .apply()
    }

    fun setFinished(message: String) {
        preferences.edit()
            .putString(KEY_MESSAGE, message)
            .putBoolean(KEY_IS_RUNNING, false)
            .apply()
    }

    fun registerListener(listener: (RuntimeStatus) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MESSAGE || key == KEY_IS_RUNNING) {
                listener(load())
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(wrapped)
        return wrapped
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "qq_auto_like_runtime"
        private const val KEY_MESSAGE = "message"
        private const val KEY_IS_RUNNING = "is_running"
    }
}
