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
        val seenCards = linkedSetOf<String>()
        val screenHeight = service.resources.displayMetrics.heightPixels
        val screenWidth = service.resources.displayMetrics.widthPixels
        var likes = 0
        var scrolls = 0
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
            if (feedConfirmed && !UiNavigator.isSpaceContextVisible(root)) {
                return ScanSummary(likes, scrolls, "已离开空间页面，当前任务已停止")
            }
            if (looksLikeSpaceFeed) {
                feedConfirmed = true
            }

            if (!feedConfirmed && UiNavigator.isProfileTopVisible(root)) {
                onStatus("当前还在空间主页顶部，继续下滑进入动态区")
            }

            val cards = classifier.extractVisibleCards(root, screenWidth, screenHeight)
            var likedThisRound = false

            for (card in cards) {
                if (stopRequested()) break
                if (seenCards.contains(card.key)) continue

                if (classifier.shouldSkip(card, config.skipAds)) {
                    markSeenIfStable(card, seenCards, screenHeight)
                    continue
                }
                if (card.isAlreadyLiked) {
                    markSeenIfStable(card, seenCards, screenHeight)
                    continue
                }
                if (config.stopOnOlderPosts && classifier.isOlderThan(card, config.maxPostAgeDays)) {
                    markSeenIfStable(card, seenCards, screenHeight)
                    continue
                }

                val likeNode = card.likeNode ?: continue
                if (!card.hasActionRow) continue

                if (gestureHelper.tap(likeNode, preferGesture = true)) {
                    likes += 1
                    likedThisRound = true
                    seenCards += card.key
                    onStatus("已点赞 $likes 条动态")
                    randomDelay.betweenLikes()
                    break
                }
            }

            if (likedThisRound) {
                continue
            }

            if (config.stopOnOlderPosts && classifier.shouldStopOnOlderCards(cards, config.maxPostAgeDays)) {
                return ScanSummary(likes, scrolls, "检测到超过 ${config.maxPostAgeDays} 天的动态")
            }

            when {
                cards.isEmpty() -> onStatus("当前屏还没识别到动态卡片，继续下滑")
                cards.none { card -> card.hasActionRow } -> onStatus("当前屏卡片还没露出操作区，继续下滑")
                cards.all { card -> classifier.shouldSkip(card, config.skipAds) } ->
                    onStatus("当前屏主要是广告卡片，继续下滑")
                else -> onStatus("当前屏没有可点赞的动态，继续下滑")
            }

            val scrollDistance = if (cards.isNotEmpty()) {
                GestureHelper.ScrollDistance.CARD
            } else {
                GestureHelper.ScrollDistance.PAGE
            }
            if (!gestureHelper.scrollDown(root, scrollDistance)) {
                return ScanSummary(likes, scrolls, "无法继续下滑")
            }

            scrolls += 1
            onStatus("继续扫描，已滑动 $scrolls 次")
            randomDelay.afterScroll()
        }

        return ScanSummary(likes, scrolls, "任务已被停止")
    }

    private fun markSeenIfStable(
        card: FeedCard,
        seenCards: MutableSet<String>,
        screenHeight: Int,
    ) {
        if (card.bounds.top < screenHeight * 0.14f) return
        if (card.bounds.bottom > screenHeight * 0.90f) return
        seenCards += card.key
    }
}
