package io.github.yanganqi.qqspaceautolike.automation

import android.view.accessibility.AccessibilityNodeInfo

class CardClassifier(
    private val blockedKeywords: Set<String> = DEFAULT_BLOCKED_KEYWORDS,
    private val postTimeParser: PostTimeParser = PostTimeParser(),
) {

    fun shouldSkip(node: AccessibilityNodeInfo, skipAds: Boolean): Boolean {
        if (!skipAds) return false
        val context = collectCardContext(node)
        return blockedKeywords.any { keyword ->
            context.contains(keyword, ignoreCase = true)
        }
    }

    fun belongsToFeedCard(node: AccessibilityNodeInfo): Boolean {
        return findLikelyCardRoot(node) != null
    }

    fun isOlderThan(node: AccessibilityNodeInfo, maxAgeDays: Int): Boolean {
        if (maxAgeDays < 0) return false
        val age = inferCardAgeDays(node) ?: return false
        return age > maxAgeDays.toLong()
    }

    fun reachedOlderContent(root: AccessibilityNodeInfo?, maxAgeDays: Int): Boolean {
        if (root == null || maxAgeDays < 0) return false
        return visibleCardAgeDays(root).any { age -> age > maxAgeDays.toLong() }
    }

    private fun collectCardContext(node: AccessibilityNodeInfo): String {
        val localContext = NodeUtils.collectContextText(node, ancestorLevels = 6)
        val cardContext = findLikelyCardRoot(node)?.let { cardRoot ->
            NodeUtils.collectSubtreeText(cardRoot, maxDepth = 5, maxNodes = 120)
        }.orEmpty()
        return "$localContext $cardContext".trim()
    }

    private fun visibleCardAgeDays(root: AccessibilityNodeInfo): List<Long> {
        val seenCards = linkedSetOf<String>()
        return NodeUtils.flatten(root).mapNotNull { node ->
            if (!node.isVisibleToUser) return@mapNotNull null
            val label = NodeUtils.combinedLabel(node)
            if (label.isBlank()) return@mapNotNull null
            if (!looksRelevantToFeed(label)) return@mapNotNull null

            val cardRoot = findLikelyCardRoot(node) ?: return@mapNotNull null
            val cardKey = stableCardKey(cardRoot)
            if (!seenCards.add(cardKey)) return@mapNotNull null

            inferCardRootAgeDays(cardRoot)
        }
    }

    private fun inferCardAgeDays(node: AccessibilityNodeInfo): Long? {
        return findLikelyCardRoot(node)?.let(::inferCardRootAgeDays)
    }

    private fun inferCardRootAgeDays(cardRoot: AccessibilityNodeInfo): Long? {
        val cardBounds = NodeUtils.bounds(cardRoot)
        val upperCardLimit = cardBounds.top + (cardBounds.height() * 0.58f).toInt()
        return NodeUtils.flatten(cardRoot)
            .asSequence()
            .filter { node -> node.isVisibleToUser }
            .mapNotNull { node ->
                val label = NodeUtils.combinedLabel(node)
                if (label.isBlank() || !postTimeParser.looksLikeTimestamp(label)) return@mapNotNull null
                val rect = NodeUtils.bounds(node)
                if (rect.isEmpty || rect.centerY() > upperCardLimit) return@mapNotNull null
                val age = postTimeParser.inferAgeDays(label) ?: return@mapNotNull null
                rect.top to age
            }
            .sortedBy { candidate -> candidate.first }
            .firstOrNull()
            ?.second
    }

    private fun looksRelevantToFeed(label: String): Boolean {
        return postTimeParser.looksLikeTimestamp(label) ||
            ACTION_MARKERS.any { marker -> label.contains(marker, ignoreCase = true) }
    }

    private fun findLikelyCardRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = NodeUtils.root(node)?.let(NodeUtils::bounds) ?: NodeUtils.bounds(node)
        val screenWidth = rootBounds.width().coerceAtLeast(1)
        val screenHeight = rootBounds.height().coerceAtLeast(1)

        return NodeUtils.ancestors(node, maxDepth = 7).firstOrNull { ancestor ->
            val rect = NodeUtils.bounds(ancestor)
            if (rect.isEmpty) return@firstOrNull false
            if (rect.width() < screenWidth * 0.55f) return@firstOrNull false
            if (rect.height() < screenHeight * 0.10f) return@firstOrNull false
            if (rect.height() > screenHeight * 0.82f) return@firstOrNull false

            val subtreeText = NodeUtils.collectSubtreeText(ancestor, maxDepth = 5, maxNodes = 120)
            ACTION_MARKERS.count { marker ->
                subtreeText.contains(marker, ignoreCase = true)
            } >= 2
        }
    }

    private fun stableCardKey(node: AccessibilityNodeInfo): String {
        val rect = NodeUtils.bounds(node)
        return "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}"
    }

    companion object {
        private val ACTION_MARKERS = listOf("点赞", "已赞", "评论", "分享", "转发")
        private val DEFAULT_BLOCKED_KEYWORDS = setOf(
            "广告",
            "推广",
            "赞助",
            "空友爱看",
            "爱看",
            "该内容来自",
            "QQ小世界",
            "小世界",
            "小游戏",
            "小程序",
            "直播",
            "热推",
            "品牌馆",
            "推荐",
            "推荐内容",
            "为你推荐",
            "黄钻",
            "续费",
            "查看详情",
            "了解更多",
            "立即打开",
            "点击进入",
            "去看看",
            "去购买",
        )
    }
}
