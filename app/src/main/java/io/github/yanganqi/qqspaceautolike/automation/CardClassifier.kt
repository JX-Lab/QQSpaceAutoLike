package io.github.yanganqi.qqspaceautolike.automation

import android.graphics.Point
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class CardClassifier(
    private val blockedKeywords: Set<String> = DEFAULT_BLOCKED_KEYWORDS,
    private val postTimeParser: PostTimeParser = PostTimeParser(),
) {

    fun extractVisibleCards(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
    ): List<FeedCard> {
        if (root == null) return emptyList()

        val candidateRoots = NodeUtils.flatten(root)
            .filter { node -> isLikelyCardRoot(node, screenWidth, screenHeight) }
            .sortedWith(
                compareBy<AccessibilityNodeInfo> { NodeUtils.bounds(it).top }
                    .thenBy { NodeUtils.bounds(it).left }
                    .thenByDescending { area(NodeUtils.bounds(it)) },
            )

        val acceptedRoots = mutableListOf<AccessibilityNodeInfo>()
        candidateRoots.forEach { candidate ->
            if (acceptedRoots.any { existing -> isSameCard(existing, candidate) }) return@forEach
            acceptedRoots += candidate
        }

        return acceptedRoots
            .sortedBy { rootNode -> NodeUtils.bounds(rootNode).top }
            .map(::buildFeedCard)
    }

    fun shouldSkip(card: FeedCard, skipAds: Boolean): Boolean {
        return skipAds && card.isAdvertisement
    }

    fun isOlderThan(card: FeedCard, maxAgeDays: Int): Boolean {
        if (maxAgeDays < 0) return false
        return (card.ageDays ?: return false) > maxAgeDays.toLong()
    }

    fun shouldStopOnOlderCards(
        cards: List<FeedCard>,
        maxAgeDays: Int,
    ): Boolean {
        if (maxAgeDays < 0) return false
        val topContentCards = cards
            .filter { card -> !card.isAdvertisement }
            .take(2)

        if (topContentCards.size < 2) return false
        return topContentCards.all { card -> card.ageDays?.let { age -> age > maxAgeDays.toLong() } == true }
    }

    private fun buildFeedCard(cardRoot: AccessibilityNodeInfo): FeedCard {
        val bounds = NodeUtils.bounds(cardRoot)
        val topMenu = findTopMenuNode(cardRoot, bounds)
        val commentBox = findCommentBoxNode(cardRoot, bounds)
        val actionAnchors = findActionAnchors(cardRoot, bounds)
        val likeNode = findLikeNode(cardRoot, bounds, actionAnchors, commentBox, topMenu)
        val likeTapPoint = findLikeTapPoint(likeNode, commentBox, actionAnchors, bounds)
        val ageDays = inferCardAgeDays(cardRoot, bounds)
        val isAdvertisement = isAdvertisementCard(cardRoot, bounds)
        val hasActionRow = commentBox != null || actionAnchors.isNotEmpty()
        val isAlreadyLiked = isAlreadyLiked(cardRoot, likeNode, bounds)

        return FeedCard(
            root = cardRoot,
            key = stableCardKey(cardRoot),
            bounds = bounds,
            likeNode = likeNode,
            likeTapPoint = likeTapPoint,
            ageDays = ageDays,
            isAdvertisement = isAdvertisement,
            isAlreadyLiked = isAlreadyLiked,
            hasActionRow = hasActionRow,
        )
    }

    private fun isLikelyCardRoot(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (!node.isVisibleToUser) return false

        val rect = NodeUtils.bounds(node)
        if (rect.isEmpty) return false
        if (rect.width() < screenWidth * 0.72f) return false
        if (rect.height() < screenHeight * 0.14f) return false
        if (rect.height() > screenHeight * 0.88f) return false
        if (rect.centerY() < screenHeight * 0.10f) return false
        if (rect.top > screenHeight * 0.94f) return false

        val topMenu = findTopMenuNode(node, rect)
        val commentBox = findCommentBoxNode(node, rect)
        val actionAnchors = findActionAnchors(node, rect)

        val hasTopMenu = topMenu != null
        val hasBottomStructure = commentBox != null || actionAnchors.isNotEmpty()
        return hasTopMenu && hasBottomStructure
    }

    private fun findTopMenuNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): AccessibilityNodeInfo? {
        val topLimit = cardBounds.top + (cardBounds.height() * 0.30f).toInt()
        val explicit = NodeUtils.flatten(cardRoot)
            .mapNotNull { node ->
                if (!node.isVisibleToUser) return@mapNotNull null
                val label = NodeUtils.combinedLabel(node)
                if (!matchesAny(label, TOP_MENU_MARKERS)) return@mapNotNull null

                val clickable = NodeUtils.findClickable(node) ?: node
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty || rect.top > topLimit) return@mapNotNull null
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.70f).toInt()) return@mapNotNull null
                clickable
            }
            .distinctBy(::nodeIdentity)
            .minByOrNull { node -> area(NodeUtils.bounds(node)) }
        if (explicit != null) return explicit

        return NodeUtils.flatten(cardRoot)
            .mapNotNull(NodeUtils::findClickable)
            .distinctBy(::nodeIdentity)
            .filter { clickable ->
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty) return@filter false
                if (rect.top > topLimit) return@filter false
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.76f).toInt()) return@filter false
                if (rect.width() > cardBounds.width() * 0.18f) return@filter false
                if (rect.height() > cardBounds.height() * 0.14f) return@filter false
                true
            }
            .minByOrNull { clickable -> area(NodeUtils.bounds(clickable)) }
    }

    private fun findCommentBoxNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): AccessibilityNodeInfo? {
        val minCommentY = cardBounds.top + (cardBounds.height() * 0.62f).toInt()
        val explicit = NodeUtils.flatten(cardRoot)
            .mapNotNull { node ->
                if (!node.isVisibleToUser) return@mapNotNull null
                val label = NodeUtils.combinedLabel(node)
                val resourceId = NodeUtils.resourceIdName(node)
                val className = NodeUtils.className(node)

                val looksLikeComment = matchesAny(label, COMMENT_INPUT_MARKERS) ||
                    resourceId.contains("comment", ignoreCase = true) ||
                    resourceId.contains("input", ignoreCase = true) ||
                    className.contains("EditText", ignoreCase = true)
                if (!looksLikeComment) return@mapNotNull null

                val clickable = NodeUtils.findClickable(node) ?: node
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty || rect.centerY() < minCommentY) return@mapNotNull null
                if (rect.width() < cardBounds.width() * 0.22f) return@mapNotNull null
                if (rect.width() > cardBounds.width() * 0.82f) return@mapNotNull null
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.25f).toInt()) return@mapNotNull null
                clickable
            }
            .distinctBy(::nodeIdentity)
            .maxByOrNull { node -> area(NodeUtils.bounds(node)) }
        if (explicit != null) return explicit

        return NodeUtils.flatten(cardRoot)
            .mapNotNull(NodeUtils::findClickable)
            .distinctBy(::nodeIdentity)
            .filter { clickable ->
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty) return@filter false
                if (rect.centerY() < minCommentY) return@filter false
                if (rect.width() < cardBounds.width() * 0.28f) return@filter false
                if (rect.width() > cardBounds.width() * 0.80f) return@filter false
                if (rect.height() > cardBounds.height() * 0.18f) return@filter false
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.28f).toInt()) return@filter false
                val label = NodeUtils.collectSubtreeText(clickable, maxDepth = 2, maxNodes = 24)
                if (matchesAny(label, TOP_MENU_MARKERS)) return@filter false
                true
            }
            .maxByOrNull { clickable -> area(NodeUtils.bounds(clickable)) }
    }

    private fun findActionAnchors(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): List<AccessibilityNodeInfo> {
        val minActionY = cardBounds.top + (cardBounds.height() * 0.45f).toInt()
        return NodeUtils.flatten(cardRoot)
            .filter { node ->
                if (!node.isVisibleToUser) return@filter false
                val label = NodeUtils.combinedLabel(node)
                if (label.isBlank()) return@filter false
                if (!matchesAny(label, ACTION_MARKERS)) return@filter false

                val rect = NodeUtils.bounds(node)
                if (rect.isEmpty || rect.centerY() < minActionY) return@filter false
                true
            }
            .distinctBy(::nodeIdentity)
            .sortedBy { node -> NodeUtils.bounds(node).centerX() }
    }

    private fun findLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        actionAnchors: List<AccessibilityNodeInfo>,
        commentBox: AccessibilityNodeInfo?,
        topMenu: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        findExplicitLikeNode(cardRoot, cardBounds)?.let { return it }
        findActionRowLikeNode(cardRoot, cardBounds, actionAnchors)?.let { return it }
        return findBottomRowLikeNode(cardRoot, cardBounds, commentBox, topMenu)
    }

    private fun findExplicitLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): AccessibilityNodeInfo? {
        val minActionY = cardBounds.top + (cardBounds.height() * 0.45f).toInt()
        return NodeUtils.flatten(cardRoot)
            .mapNotNull { node ->
                if (!node.isVisibleToUser) return@mapNotNull null
                val label = NodeUtils.combinedLabel(node)
                if (label.isBlank()) return@mapNotNull null
                if (!matchesAny(label, LIKE_KEYWORDS)) return@mapNotNull null
                if (matchesAny(label, NEGATIVE_KEYWORDS)) return@mapNotNull null

                val clickable = NodeUtils.findClickable(node) ?: return@mapNotNull null
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty) return@mapNotNull null
                if (rect.centerY() < minActionY) return@mapNotNull null
                if (rect.width() > cardBounds.width() * 0.36f) return@mapNotNull null
                if (rect.height() > cardBounds.height() * 0.26f) return@mapNotNull null
                clickable
            }
            .distinctBy(::nodeIdentity)
            .sortedWith(
                compareBy<AccessibilityNodeInfo> { abs(NodeUtils.bounds(it).centerY() - cardBounds.bottom) }
                    .thenByDescending { NodeUtils.bounds(it).centerX() },
            )
            .firstOrNull()
    }

    private fun findActionRowLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        actionAnchors: List<AccessibilityNodeInfo>,
    ): AccessibilityNodeInfo? {
        if (actionAnchors.size < 2) return null

        val anchorClickables = actionAnchors
            .mapNotNull(NodeUtils::findClickable)
            .distinctBy(::nodeIdentity)
        if (anchorClickables.isEmpty()) return null

        val rowCenterY = anchorClickables
            .map { clickable -> NodeUtils.bounds(clickable).centerY() }
            .average()
            .toInt()
        val leftmostLabeledX = anchorClickables.minOf { clickable -> NodeUtils.bounds(clickable).centerX() }

        return NodeUtils.flatten(cardRoot)
            .mapNotNull(NodeUtils::findClickable)
            .distinctBy(::nodeIdentity)
            .filter { clickable ->
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty) return@filter false
                if (rect.centerY() < cardBounds.top + cardBounds.height() * 0.45f) return@filter false
                if (abs(rect.centerY() - rowCenterY) > cardBounds.height() * 0.12f) return@filter false
                if (rect.width() > cardBounds.width() * 0.26f) return@filter false
                if (rect.height() > cardBounds.height() * 0.24f) return@filter false
                if (rect.centerX() >= leftmostLabeledX) return@filter false

                val label = NodeUtils.collectSubtreeText(clickable, maxDepth = 2, maxNodes = 24)
                if (matchesAny(label, ACTION_MARKERS)) return@filter false
                if (matchesAny(label, NEGATIVE_KEYWORDS)) return@filter false
                true
            }
            .sortedByDescending { clickable -> NodeUtils.bounds(clickable).centerX() }
            .firstOrNull()
    }

    private fun findBottomRowLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        commentBox: AccessibilityNodeInfo?,
        topMenu: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val commentBounds = commentBox?.let(NodeUtils::bounds) ?: return null
        val menuIdentity = topMenu?.let(::nodeIdentity)

        return NodeUtils.flatten(cardRoot)
            .mapNotNull(NodeUtils::findClickable)
            .distinctBy(::nodeIdentity)
            .filter { clickable ->
                val rect = NodeUtils.bounds(clickable)
                if (rect.isEmpty) return@filter false
                if (nodeIdentity(clickable) == menuIdentity) return@filter false
                if (rect.centerY() < commentBounds.top - commentBounds.height()) return@filter false
                if (rect.centerY() > commentBounds.bottom + commentBounds.height() / 2) return@filter false
                if (rect.centerX() <= commentBounds.right) return@filter false
                if (rect.width() > cardBounds.width() * 0.20f) return@filter false
                if (rect.height() > cardBounds.height() * 0.18f) return@filter false

                val label = NodeUtils.collectSubtreeText(clickable, maxDepth = 2, maxNodes = 24)
                if (matchesAny(label, COMMENT_INPUT_MARKERS)) return@filter false
                if (matchesAny(label, TOP_MENU_MARKERS)) return@filter false
                true
            }
            .sortedByDescending { clickable -> NodeUtils.bounds(clickable).centerX() }
            .firstOrNull()
    }

    private fun findLikeTapPoint(
        likeNode: AccessibilityNodeInfo?,
        commentBox: AccessibilityNodeInfo?,
        actionAnchors: List<AccessibilityNodeInfo>,
        cardBounds: Rect,
    ): Point? {
        likeNode?.let { node ->
            val rect = NodeUtils.bounds(node)
            if (!rect.isEmpty) return Point(rect.centerX(), rect.centerY())
        }

        commentBox?.let { node ->
            val rect = NodeUtils.bounds(node)
            if (!rect.isEmpty) {
                val tapX = (cardBounds.right - cardBounds.width() * 0.11f).toInt()
                val tapY = rect.centerY()
                return Point(tapX, tapY)
            }
        }

        if (actionAnchors.isNotEmpty()) {
            val rowCenterY = actionAnchors
                .map { anchor -> NodeUtils.bounds(anchor).centerY() }
                .average()
                .toInt()
            val tapX = (cardBounds.right - cardBounds.width() * 0.11f).toInt()
            return Point(tapX, rowCenterY)
        }

        return null
    }

    private fun inferCardAgeDays(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): Long? {
        val upperCardLimit = cardBounds.top + (cardBounds.height() * 0.48f).toInt()
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

    private fun isAdvertisementCard(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): Boolean {
        val topBadgeDetected = NodeUtils.flatten(cardRoot).any { node ->
            if (!node.isVisibleToUser) return@any false
            val label = NodeUtils.combinedLabel(node)
            if (label.isBlank() || !matchesAny(label, AD_BADGE_KEYWORDS)) return@any false

            val rect = NodeUtils.bounds(node)
            rect.top <= cardBounds.top + (cardBounds.height() * 0.35f).toInt() &&
                rect.centerX() >= cardBounds.left + (cardBounds.width() * 0.58f).toInt()
        }
        if (topBadgeDetected) return true

        val cardText = NodeUtils.collectSubtreeText(cardRoot, maxDepth = 6, maxNodes = 180)
        val blockedHits = blockedKeywords.count { keyword ->
            cardText.contains(keyword, ignoreCase = true)
        }
        val marketingCallToAction = matchesAny(cardText, MARKETING_CALL_TO_ACTION)

        return blockedHits >= 2 || (blockedHits >= 1 && marketingCallToAction)
    }

    private fun isAlreadyLiked(
        cardRoot: AccessibilityNodeInfo,
        likeNode: AccessibilityNodeInfo?,
        cardBounds: Rect,
    ): Boolean {
        val directLabel = likeNode?.let(NodeUtils::combinedLabel).orEmpty()
        if (matchesAny(directLabel, NEGATIVE_KEYWORDS + ALREADY_LIKED_KEYWORDS)) return true
        if (likeNode?.isSelected == true || likeNode?.isChecked == true) return true

        val minActionY = cardBounds.top + (cardBounds.height() * 0.45f).toInt()
        return NodeUtils.flatten(cardRoot).any { node ->
            if (!node.isVisibleToUser) return@any false
            val rect = NodeUtils.bounds(node)
            if (rect.isEmpty || rect.centerY() < minActionY) return@any false

            val label = NodeUtils.combinedLabel(node)
            matchesAny(label, ALREADY_LIKED_KEYWORDS) || matchesAny(label, NEGATIVE_KEYWORDS)
        }
    }

    private fun stableCardKey(cardRoot: AccessibilityNodeInfo): String {
        val signature = NodeUtils.collectSubtreeText(cardRoot, maxDepth = 5, maxNodes = 100)
            .replace(WHITESPACE_REGEX, "")
            .take(180)

        if (signature.isNotBlank()) return signature

        val rect = NodeUtils.bounds(cardRoot)
        return "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}"
    }

    private fun isSameCard(
        existing: AccessibilityNodeInfo,
        candidate: AccessibilityNodeInfo,
    ): Boolean {
        val existingRect = NodeUtils.bounds(existing)
        val candidateRect = NodeUtils.bounds(candidate)

        if (existingRect.contains(candidateRect.centerX(), candidateRect.centerY())) return true
        if (candidateRect.contains(existingRect.centerX(), existingRect.centerY())) return true
        return overlapRatio(existingRect, candidateRect) >= 0.82f
    }

    private fun overlapRatio(first: Rect, second: Rect): Float {
        val overlapLeft = maxOf(first.left, second.left)
        val overlapTop = maxOf(first.top, second.top)
        val overlapRight = minOf(first.right, second.right)
        val overlapBottom = minOf(first.bottom, second.bottom)
        val width = (overlapRight - overlapLeft).coerceAtLeast(0)
        val height = (overlapBottom - overlapTop).coerceAtLeast(0)
        if (width == 0 || height == 0) return 0f

        val overlapArea = width.toLong() * height.toLong()
        val smallerArea = minOf(area(first), area(second)).coerceAtLeast(1L)
        return overlapArea.toFloat() / smallerArea.toFloat()
    }

    private fun matchesAny(
        text: String,
        keywords: Collection<String>,
    ): Boolean {
        return keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun nodeIdentity(node: AccessibilityNodeInfo): String {
        val rect = NodeUtils.bounds(node)
        val label = NodeUtils.combinedLabel(node)
        return "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}|$label"
    }

    private fun area(rect: Rect): Long {
        return rect.width().toLong().coerceAtLeast(0L) * rect.height().toLong().coerceAtLeast(0L)
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val LIKE_KEYWORDS = listOf("点赞", "赞", "like")
        private val ACTION_MARKERS = listOf("点赞", "已赞", "评论", "转发", "分享")
        private val COMMENT_INPUT_MARKERS = listOf("评论", "说点什么", "写评论", "输入", "回复")
        private val TOP_MENU_MARKERS = listOf("更多", "菜单", "更多操作", "更多选项")
        private val ALREADY_LIKED_KEYWORDS = listOf("已赞")
        private val NEGATIVE_KEYWORDS = listOf("取消赞", "收回赞", "赞了", "赞过", "赞同")
        private val AD_BADGE_KEYWORDS = listOf("广告", "推广", "赞助")
        private val MARKETING_CALL_TO_ACTION = listOf(
            "查看详情",
            "了解更多",
            "立即打开",
            "点击进入",
            "去看看",
            "去购买",
        )
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
