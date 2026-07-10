package io.github.yanganqi.qqspaceautolike.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import io.github.yanganqi.qqspaceautolike.config.AppConfig

class FeedScanner(
    private val service: AccessibilityService,
    private val gestureHelper: GestureHelper,
    private val randomDelay: RandomDelay,
    private val classifier: CardClassifier,
    private val config: AppConfig,
    private val stopRequested: () -> Boolean,
) {

    suspend fun scan(
        rootProvider: () -> AccessibilityNodeInfo?,
        onStatus: (String) -> Unit,
    ): ScanSummary {
        val deadline = config.runDuration.minutes?.let { System.currentTimeMillis() + it * 60_000L }
        val seenNodes = linkedSetOf<String>()
        var likes = 0
        var scrolls = 0
        var idleRounds = 0

        while (!stopRequested()) {
            if (deadline != null && System.currentTimeMillis() >= deadline) {
                return ScanSummary(likes, scrolls, "达到设定运行时长")
            }

            val root = rootProvider()
            if (root == null) {
                onStatus("等待空间动态页面加载")
                randomDelay.shortWait()
                continue
            }

            if (config.stopOnOlderPosts && classifier.reachedOlderContent(root, config.maxPostAgeDays)) {
                return ScanSummary(likes, scrolls, "检测到超过 ${config.maxPostAgeDays} 天的动态")
            }

            var likedThisRound = 0
            findLikeCandidates(root).forEach { node ->
                if (stopRequested()) return@forEach

                val key = NodeUtils.stableKey(node)
                if (!seenNodes.add(key)) return@forEach
                if (classifier.shouldSkip(node, config.skipAds)) return@forEach
                if (isAlreadyLiked(node)) return@forEach

                if (gestureHelper.tap(node)) {
                    likes += 1
                    likedThisRound += 1
                    onStatus("已点赞 $likes 条动态")
                    randomDelay.betweenLikes()
                }
            }

            idleRounds = if (likedThisRound == 0) idleRounds + 1 else 0
            if (config.singlePassPerOpen && idleRounds >= MAX_IDLE_ROUNDS) {
                return ScanSummary(likes, scrolls, "连续多次未发现可点赞内容")
            }

            if (!gestureHelper.scrollDown(root)) {
                return ScanSummary(likes, scrolls, "无法继续下滑")
            }

            scrolls += 1
            onStatus("继续扫描，已滑动 $scrolls 次")
            randomDelay.afterScroll()
        }

        return ScanSummary(likes, scrolls, "任务已被停止")
    }

    private fun findLikeCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels

        return NodeUtils.flatten(root).filter { node ->
            if (!node.isVisibleToUser) return@filter false

            val label = NodeUtils.combinedLabel(node)
            if (label.isBlank()) return@filter false
            if (!LIKE_KEYWORDS.any { label.contains(it, ignoreCase = true) }) return@filter false
            if (NEGATIVE_KEYWORDS.any { label.contains(it, ignoreCase = true) }) return@filter false
            if (NodeUtils.findClickable(node) == null) return@filter false

            val rect = NodeUtils.bounds(node)
            if (rect.width() <= 0 || rect.height() <= 0) return@filter false
            if (rect.centerX() < width * 0.42f) return@filter false
            if (rect.height() > height * 0.22f) return@filter false
            if (rect.width() > width * 0.55f) return@filter false
            true
        }
    }

    private fun isAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        val label = NodeUtils.combinedLabel(node)
        return node.isSelected ||
            node.isChecked ||
            label.contains("已赞") ||
            label.contains("取消赞") ||
            label.contains("收回赞")
    }

    companion object {
        private const val MAX_IDLE_ROUNDS = 4
        private val LIKE_KEYWORDS = listOf("点赞", "赞", "like")
        private val NEGATIVE_KEYWORDS = listOf("取消赞", "收回赞", "赞了", "赞过", "赞同")
    }
}

