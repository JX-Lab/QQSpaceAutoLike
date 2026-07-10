package io.github.yanganqi.qqspaceautolike.automation

import android.view.accessibility.AccessibilityNodeInfo
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CardClassifier(
    private val blockedKeywords: Set<String> = DEFAULT_BLOCKED_KEYWORDS,
) {

    fun shouldSkip(node: AccessibilityNodeInfo, skipAds: Boolean): Boolean {
        if (!skipAds) return false
        val context = NodeUtils.collectContextText(node)
        return blockedKeywords.any { keyword ->
            context.contains(keyword, ignoreCase = true)
        }
    }

    fun reachedOlderContent(root: AccessibilityNodeInfo?, maxAgeDays: Int): Boolean {
        if (root == null || maxAgeDays < 0) return false
        return NodeUtils.allTexts(root).any { text ->
            val age = inferAgeDays(text) ?: return@any false
            age > maxAgeDays
        }
    }

    private fun inferAgeDays(text: String): Long? {
        val normalized = text.replace(" ", "")
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
                val today = LocalDate.now()
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
                ChronoUnit.DAYS.between(candidate, LocalDate.now())
            } catch (_: DateTimeException) {
                null
            }
        }

        SHORT_DATE.find(normalized)?.let { match ->
            return try {
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                val today = LocalDate.now()
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

    companion object {
        private val DAY_AGO = Regex("""(\d+)天前""")
        private val MONTH_DAY = Regex("""(\d{1,2})月(\d{1,2})日""")
        private val FULL_DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
        private val SHORT_DATE = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)""")
        private val DEFAULT_BLOCKED_KEYWORDS = setOf(
            "广告",
            "推广",
            "空友爱看",
            "QQ小世界",
            "小世界",
            "小游戏",
            "小程序",
            "直播",
            "热推",
            "品牌馆",
            "推荐",
            "黄钻",
            "续费",
        )
    }
}
