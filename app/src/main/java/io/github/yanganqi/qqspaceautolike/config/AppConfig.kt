package io.github.yanganqi.qqspaceautolike.config

enum class RunDuration(val preferenceValue: String, val minutes: Long?) {
    MINUTES_5("5", 5),
    MINUTES_10("10", 10),
    MINUTES_15("15", 15),
    MINUTES_30("30", 30),
    UNLIMITED("unlimited", null),
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
) {
    companion object {
        const val DEFAULT_MAX_POST_AGE_DAYS = 3
    }
}

