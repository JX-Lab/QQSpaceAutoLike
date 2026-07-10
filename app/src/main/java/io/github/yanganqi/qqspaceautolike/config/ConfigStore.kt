package io.github.yanganqi.qqspaceautolike.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ConfigStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppConfig {
        return AppConfig(
            autoRunOnQqOpen = preferences.getBoolean(KEY_AUTO_RUN, true),
            skipAds = preferences.getBoolean(KEY_SKIP_ADS, true),
            randomDelay = preferences.getBoolean(KEY_RANDOM_DELAY, true),
            singlePassPerOpen = preferences.getBoolean(KEY_SINGLE_PASS, true),
            stopOnOlderPosts = preferences.getBoolean(KEY_STOP_ON_OLD, true),
            maxPostAgeDays = preferences.getInt(KEY_MAX_POST_AGE_DAYS, AppConfig.DEFAULT_MAX_POST_AGE_DAYS),
            runDuration = RunDuration.fromPreference(preferences.getString(KEY_RUN_DURATION, RunDuration.MINUTES_10.preferenceValue)),
        )
    }

    fun save(config: AppConfig) {
        preferences.edit {
            putBoolean(KEY_AUTO_RUN, config.autoRunOnQqOpen)
            putBoolean(KEY_SKIP_ADS, config.skipAds)
            putBoolean(KEY_RANDOM_DELAY, config.randomDelay)
            putBoolean(KEY_SINGLE_PASS, config.singlePassPerOpen)
            putBoolean(KEY_STOP_ON_OLD, config.stopOnOlderPosts)
            putInt(KEY_MAX_POST_AGE_DAYS, config.maxPostAgeDays)
            putString(KEY_RUN_DURATION, config.runDuration.preferenceValue)
        }
    }

    fun registerListener(listener: (AppConfig) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            listener(load())
        }
        preferences.registerOnSharedPreferenceChangeListener(wrapped)
        return wrapped
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "qq_space_auto_like"
        private const val KEY_AUTO_RUN = "auto_run_on_qq_open"
        private const val KEY_SKIP_ADS = "skip_ads"
        private const val KEY_RANDOM_DELAY = "random_delay"
        private const val KEY_SINGLE_PASS = "single_pass"
        private const val KEY_STOP_ON_OLD = "stop_on_old"
        private const val KEY_MAX_POST_AGE_DAYS = "max_post_age_days"
        private const val KEY_RUN_DURATION = "run_duration"
    }
}

