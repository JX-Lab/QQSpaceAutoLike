package io.github.yanganqi.qqspaceautolike.automation

import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class PostTimeParser(
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun inferAgeDays(text: String): Long? {
        val normalized = normalize(text)
        when {
            normalized.contains("刚刚") -> return 0
            normalized.contains("今天") -> return 0
            normalized.contains("分钟前") -> return 0
            normalized.contains("小时前") -> return 0
            normalized.contains("昨天") -> return 1
            normalized.contains("前天") -> return 2
        }

        DAY_AGO.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }

        MONTH_DAY.find(normalized)?.let { match ->
            return try {
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                val today = LocalDate.now(clock)
                var candidate = LocalDate.of(today.year, month, day)
                if (candidate.isAfter(today)) {
                    candidate = candidate.minusYears(1)
                }
                ChronoUnit.DAYS.between(candidate, today)
            } catch (_: DateTimeException) {
                null
            }
        }

        FULL_DATE.find(normalized)?.let { match ->
            return try {
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val day = match.groupValues[3].toInt()
                val candidate = LocalDate.of(year, month, day)
                ChronoUnit.DAYS.between(candidate, LocalDate.now(clock))
            } catch (_: DateTimeException) {
                null
            }
        }

        SHORT_DATE.find(normalized)?.let { match ->
            return try {
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                val today = LocalDate.now(clock)
                var candidate = LocalDate.of(today.year, month, day)
                if (candidate.isAfter(today)) {
                    candidate = candidate.minusYears(1)
                }
                ChronoUnit.DAYS.between(candidate, today)
            } catch (_: DateTimeException) {
                null
            }
        }

        return null
    }

    fun looksLikeTimestamp(text: String): Boolean {
        val normalized = normalize(text)
        return RELATIVE_TIME_MARKERS.any { normalized.contains(it) } ||
            DAY_AGO.containsMatchIn(normalized) ||
            MONTH_DAY.containsMatchIn(normalized) ||
            FULL_DATE.containsMatchIn(normalized) ||
            SHORT_DATE.containsMatchIn(normalized)
    }

    private fun normalize(text: String): String {
        return text
            .replace('\u00A0', ' ')
            .replace('：', ':')
            .replace(" ", "")
            .trim()
    }

    companion object {
        private val DAY_AGO = Regex("""(\d+)天前""")
        private val MONTH_DAY = Regex("""(\d{1,2})月(\d{1,2})日""")
        private val FULL_DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
        private val SHORT_DATE = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)""")
        private val RELATIVE_TIME_MARKERS = listOf(
            "刚刚",
            "今天",
            "昨天",
            "前天",
            "分钟前",
            "小时前",
            "天前",
        )
    }
}
