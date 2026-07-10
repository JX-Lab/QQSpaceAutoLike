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

data class AppConfig(
    val autoRunOnQqOpen: Boolean = true,
    val skipAds: Boolean = true,
    val randomDelay: Boolean = true,
    val singlePassPerOpen: Boolean = true,
    val stopOnOlderPosts: Boolean = true,
    val maxPostAgeDays: Int = DEFAULT_MAX_POST_AGE_DAYS,
    val runDuration: RunDuration = RunDuration.MINUTES_10,
    val customRunMinutes: Int = DEFAULT_CUSTOM_RUN_MINUTES,
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
    }
}
