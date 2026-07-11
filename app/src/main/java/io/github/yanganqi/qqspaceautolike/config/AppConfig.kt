package io.github.yanganqi.qqspaceautolike.config

enum class RunDuration(val preferenceValue: String, val minutes: Long?) {
    MINUTES_5("5", 5),
    MINUTES_10("10", 10),
    CUSTOM("custom", null),
    ;

    companion object {
        fun fromPreference(value: String?): RunDuration {
            return entries.firstOrNull { it.preferenceValue == value } ?: MINUTES_10
        }
    }
}

enum class AutomationMode(val preferenceValue: String) {
    LEGACY_UI("legacy_ui"),
    QZONE_QUEUE("qzone_queue"),
    ;

    companion object {
        fun fromPreference(value: String?): AutomationMode {
            return entries.firstOrNull { it.preferenceValue == value } ?: QZONE_QUEUE
        }
    }
}

data class AppConfig(
    val mode: AutomationMode = AutomationMode.QZONE_QUEUE,
    val autoRunOnQqOpen: Boolean = true,
    val skipAds: Boolean = true,
    val randomDelay: Boolean = true,
    val singlePassPerOpen: Boolean = true,
    val stopOnOlderPosts: Boolean = true,
    val maxPostAgeDays: Int = DEFAULT_MAX_POST_AGE_DAYS,
    val runDuration: RunDuration = RunDuration.MINUTES_10,
    val customRunMinutes: Int = DEFAULT_CUSTOM_RUN_MINUTES,
    val myQq: String = "",
    val qzoneCookie: String = "",
    val pollIntervalMinutes: Int = DEFAULT_POLL_INTERVAL_MINUTES,
    val minLikeAgeMinutes: Int = DEFAULT_MIN_LIKE_AGE_MINUTES,
    val maxLikesPerSession: Int = DEFAULT_MAX_LIKES_PER_SESSION,
    val queueRetentionHours: Int = DEFAULT_QUEUE_RETENTION_HOURS,
) {
    fun effectiveRunMinutes(): Long? {
        return when (runDuration) {
            RunDuration.CUSTOM -> customRunMinutes.coerceIn(MIN_CUSTOM_RUN_MINUTES, MAX_CUSTOM_RUN_MINUTES).toLong()
            else -> runDuration.minutes
        }
    }

    companion object {
        const val DEFAULT_MAX_POST_AGE_DAYS = 3
        const val DEFAULT_CUSTOM_RUN_MINUTES = 10
        const val MIN_CUSTOM_RUN_MINUTES = 1
        const val MAX_CUSTOM_RUN_MINUTES = 120
        const val DEFAULT_POLL_INTERVAL_MINUTES = 3
        const val MIN_POLL_INTERVAL_MINUTES = 1
        const val MAX_POLL_INTERVAL_MINUTES = 30
        const val DEFAULT_MIN_LIKE_AGE_MINUTES = 20
        const val MIN_MIN_LIKE_AGE_MINUTES = 1
        const val MAX_MIN_LIKE_AGE_MINUTES = 24 * 60
        const val DEFAULT_MAX_LIKES_PER_SESSION = 8
        const val MIN_MAX_LIKES_PER_SESSION = 1
        const val MAX_MAX_LIKES_PER_SESSION = 50
        const val DEFAULT_QUEUE_RETENTION_HOURS = 24
    }
}
