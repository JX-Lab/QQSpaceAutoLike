package io.github.yanganqi.qqspaceautolike.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.yanganqi.qqspaceautolike.config.AppConfig
import kotlin.math.abs

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

                if (!card.hasActionRow) continue
                if (attemptLike(card, rootProvider, screenWidth, screenHeight)) {
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

            val scrollDistance = resolveScrollDistance(cards, seenCards, screenHeight)
            if (scrollDistance == GestureHelper.ScrollDistance.REVEAL) {
                onStatus("当前屏最下方卡片还没露全，先小幅补位")
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

    private suspend fun attemptLike(
        card: FeedCard,
        rootProvider: () -> AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        card.likeNode?.let { likeNode ->
            if (gestureHelper.tap(likeNode, preferGesture = true) &&
                verifyLikeApplied(card, rootProvider, screenWidth, screenHeight)
            ) {
                return true
            }
        }

        for (point in card.likeTapPoints) {
            if (!gestureHelper.tap(point.x, point.y)) continue
            if (verifyLikeApplied(card, rootProvider, screenWidth, screenHeight)) {
                return true
            }
        }

        return false
    }

    private suspend fun verifyLikeApplied(
        referenceCard: FeedCard,
        rootProvider: () -> AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        repeat(2) {
            randomDelay.shortWait()
            val refreshedRoot = rootProvider() ?: return@repeat
            val refreshedCards = classifier.extractVisibleCards(refreshedRoot, screenWidth, screenHeight)
            val matchingCard = findMatchingCard(referenceCard, refreshedCards)
            if (matchingCard?.isAlreadyLiked == true) {
                return true
            }
        }
        return false
    }

    private fun findMatchingCard(
        referenceCard: FeedCard,
        cards: List<FeedCard>,
    ): FeedCard? {
        cards.firstOrNull { card -> card.key == referenceCard.key }?.let { return it }
        return cards
            .filter { card -> overlapRatio(referenceCard.bounds, card.bounds) >= 0.55f }
            .maxByOrNull { card ->
                overlapRatio(referenceCard.bounds, card.bounds) -
                    (abs(referenceCard.bounds.top - card.bounds.top) / 10_000f)
            }
    }

    private fun resolveScrollDistance(
        cards: List<FeedCard>,
        seenCards: Set<String>,
        screenHeight: Int,
    ): GestureHelper.ScrollDistance {
        if (cards.isEmpty()) return GestureHelper.ScrollDistance.PAGE

        val revealCandidate = cards.lastOrNull { card ->
            !seenCards.contains(card.key) &&
                !classifier.shouldSkip(card, config.skipAds) &&
                !card.isAlreadyLiked &&
                !(config.stopOnOlderPosts && classifier.isOlderThan(card, config.maxPostAgeDays))
        }

        return if (revealCandidate != null && revealCandidate.bounds.bottom >= screenHeight * 0.94f) {
            GestureHelper.ScrollDistance.REVEAL
        } else {
            GestureHelper.ScrollDistance.CARD
        }
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

    private fun overlapRatio(
        first: Rect,
        second: Rect,
    ): Float {
        val overlapLeft = maxOf(first.left, second.left)
        val overlapTop = maxOf(first.top, second.top)
        val overlapRight = minOf(first.right, second.right)
        val overlapBottom = minOf(first.bottom, second.bottom)
        val width = (overlapRight - overlapLeft).coerceAtLeast(0)
        val height = (overlapBottom - overlapTop).coerceAtLeast(0)
        if (width == 0 || height == 0) return 0f

        val overlapArea = width.toLong() * height.toLong()
        val smallerArea = minOf(
            first.width().toLong().coerceAtLeast(1L) * first.height().toLong().coerceAtLeast(1L),
            second.width().toLong().coerceAtLeast(1L) * second.height().toLong().coerceAtLeast(1L),
        )
        return overlapArea.toFloat() / smallerArea.toFloat()
    }
}
