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
        val deadline = config.effectiveRunMinutes()?.let { System.currentTimeMillis() + it * 60_000L }
        val seenNodes = linkedSetOf<String>()
        var likes = 0
        var scrolls = 0
        var idleRounds = 0
        var feedConfirmed = false

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

            val looksLikeSpaceFeed = UiNavigator.isLikelySpaceFeed(root)
            if (feedConfirmed && !looksLikeSpaceFeed && looksLikeManualInterruption(root)) {
                return ScanSummary(likes, scrolls, "检测到你已切到其他 QQ 页面，当前任务已停止")
            }
            if (looksLikeSpaceFeed) {
                feedConfirmed = true
            }

            if (config.stopOnOlderPosts && classifier.reachedOlderContent(root, config.maxPostAgeDays)) {
                return ScanSummary(likes, scrolls, "检测到超过 ${config.maxPostAgeDays} 天的动态")
            }

            val visibleActionBar = hasVisibleActionBar(root)
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

            idleRounds = when {
                likedThisRound > 0 -> 0
                !visibleActionBar -> 0
                else -> idleRounds + 1
            }
            if (likedThisRound == 0 && !visibleActionBar) {
                onStatus("当前页面还没露出点赞区，继续下滑")
            }
            if (config.singlePassPerOpen && visibleActionBar && idleRounds >= MAX_IDLE_ROUNDS) {
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

    private fun hasVisibleActionBar(root: AccessibilityNodeInfo): Boolean {
        return NodeUtils.allTexts(root).any { text ->
            ACTION_ROW_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }
    }

    private fun looksLikeManualInterruption(root: AccessibilityNodeInfo): Boolean {
        val allTexts = NodeUtils.allTexts(root)
        val hits = INTERRUPTION_MARKERS.count { marker ->
            allTexts.any { text -> text.contains(marker, ignoreCase = true) }
        }
        return hits >= 2
    }

    private fun findLikeCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        val result = linkedMapOf<String, AccessibilityNodeInfo>()

        NodeUtils.flatten(root).forEach { node ->
            if (!node.isVisibleToUser) return@forEach
            val target = NodeUtils.findClickable(node) ?: return@forEach

            if (!looksLikeLikeAction(node, target, width, height)) return@forEach

            result.putIfAbsent(NodeUtils.stableKey(target), target)
        }
        return result.values.toList()
    }

    private fun looksLikeLikeAction(
        node: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        width: Int,
        height: Int,
    ): Boolean {
        val rect = NodeUtils.bounds(target)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.centerX() < width * 0.52f) return false
        if (rect.top < height * 0.12f) return false
        if (rect.height() > height * 0.20f) return false
        if (rect.width() > width * 0.60f) return false

        val semanticText = buildString {
            append(NodeUtils.semanticLabel(node))
            append(' ')
            append(NodeUtils.semanticLabel(target))
        }.trim()
        val contextText = NodeUtils.collectContextText(target)
        val combinedContext = "$semanticText $contextText"

        if (NEGATIVE_KEYWORDS.any { combinedContext.contains(it, ignoreCase = true) }) return false

        val hasDirectLikeSignal = LIKE_KEYWORDS.any { semanticText.contains(it, ignoreCase = true) }
        val hasContextLikeSignal = LIKE_KEYWORDS.any { contextText.contains(it, ignoreCase = true) }
        val hasResourceIdLikeSignal = RESOURCE_ID_KEYWORDS.any { keyword ->
            semanticText.contains(keyword, ignoreCase = true)
        }
        val hasActionRowSignal = ACTION_ROW_KEYWORDS.any { contextText.contains(it, ignoreCase = true) }
        val looksLikeIcon =
            NodeUtils.className(node).contains("Image", ignoreCase = true) ||
                NodeUtils.className(target).contains("Image", ignoreCase = true) ||
                rect.width() < width * 0.22f

        var score = 0
        if (hasDirectLikeSignal) score += 4
        if (hasContextLikeSignal) score += 2
        if (hasResourceIdLikeSignal) score += 3
        if (hasActionRowSignal) score += 1
        if (looksLikeIcon) score += 1
        if (rect.centerX() > width * 0.72f) score += 1

        if (hasDirectLikeSignal || hasContextLikeSignal || hasResourceIdLikeSignal) {
            return score >= 3
        }

        // Fallback for pure icon buttons on the action bar: QQ may expose only an image node.
        return hasActionRowSignal &&
            looksLikeIcon &&
            rect.centerX() > width * 0.76f &&
            rect.width() < width * 0.20f
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
        private const val MAX_IDLE_ROUNDS = 6
        private val LIKE_KEYWORDS = listOf("点赞", "赞", "like")
        private val RESOURCE_ID_KEYWORDS = listOf("like", "zan", "praise", "thumb")
        private val ACTION_ROW_KEYWORDS = listOf("评论", "转发", "分享")
        private val INTERRUPTION_MARKERS = listOf("消息", "联系人", "群聊", "发送", "搜索")
        private val NEGATIVE_KEYWORDS = listOf("取消赞", "收回赞", "赞了", "赞过", "赞同")
    }
}
