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
        val bottomAvatar = findBottomAvatarNode(cardRoot, bounds, commentBox)
        val actionAnchors = findActionAnchors(cardRoot, bounds)
        val likeNode = findLikeNode(cardRoot, bounds, actionAnchors, commentBox, topMenu, bottomAvatar)
        val likeTapPoints = findLikeTapPoints(commentBox, bottomAvatar, actionAnchors, bounds)
        val ageDays = inferCardAgeDays(cardRoot, bounds)
        val isAdvertisement = isAdvertisementCard(cardRoot, bounds)
        val hasActionRow = commentBox != null || actionAnchors.isNotEmpty()
        val isAlreadyLiked = isAlreadyLiked(cardRoot, likeNode, bounds)

        return FeedCard(
            root = cardRoot,
            key = stableCardKey(cardRoot),
            bounds = bounds,
            likeNode = likeNode,
            likeTapPoints = likeTapPoints,
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
        val bottomAvatar = findBottomAvatarNode(node, rect, commentBox)
        val actionAnchors = findActionAnchors(node, rect)

        val hasTopMenu = topMenu != null
        val hasStructuredCommentBar = commentBox != null && bottomAvatar != null
        val hasBottomStructure = hasStructuredCommentBar || commentBox != null || actionAnchors.isNotEmpty()
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

                val target = compactTapTarget(node)
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty || rect.top > topLimit) return@mapNotNull null
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.70f).toInt()) return@mapNotNull null
                target
            }
            .distinctBy(::nodeIdentity)
            .minByOrNull { node -> area(NodeUtils.bounds(node)) }
        if (explicit != null) return explicit

        return NodeUtils.flatten(cardRoot)
            .map(::compactTapTarget)
            .distinctBy(::nodeIdentity)
            .filter { target ->
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty) return@filter false
                if (rect.top > topLimit) return@filter false
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.76f).toInt()) return@filter false
                if (rect.width() > cardBounds.width() * 0.18f) return@filter false
                if (rect.height() > cardBounds.height() * 0.14f) return@filter false
                true
            }
            .minByOrNull { target -> area(NodeUtils.bounds(target)) }
    }

    private fun findCommentBoxNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
    ): AccessibilityNodeInfo? {
        val nodes = NodeUtils.flatten(cardRoot)
        val minCommentY = cardBounds.top + (cardBounds.height() * 0.54f).toInt()
        val explicit = nodes
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

                val target = compactTapTarget(node)
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty || rect.centerY() < minCommentY) return@mapNotNull null
                if (rect.width() < cardBounds.width() * 0.20f) return@mapNotNull null
                if (rect.width() > cardBounds.width() * 0.86f) return@mapNotNull null
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.22f).toInt()) return@mapNotNull null
                target
            }
            .distinctBy(::nodeIdentity)
            .maxByOrNull { node -> area(NodeUtils.bounds(node)) }
        if (explicit != null) return explicit

        return nodes
            .filter { node -> node.isVisibleToUser }
            .map(::compactTapTarget)
            .distinctBy(::nodeIdentity)
            .filter { target ->
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty) return@filter false
                if (rect.centerY() < minCommentY) return@filter false
                if (rect.width() < cardBounds.width() * 0.20f) return@filter false
                if (rect.width() > cardBounds.width() * 0.88f) return@filter false
                if (rect.height() > maxOf((cardBounds.height() * 0.22f).toInt(), 96)) return@filter false
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.24f).toInt()) return@filter false

                val label = NodeUtils.collectSubtreeText(target, maxDepth = 2, maxNodes = 24)
                if (matchesAny(label, TOP_MENU_MARKERS)) return@filter false
                if (matchesAny(label, NEGATIVE_KEYWORDS)) return@filter false
                true
            }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { target ->
                    scoreCommentBoxCandidate(cardRoot, cardBounds, target)
                }.thenByDescending { target ->
                    area(NodeUtils.bounds(target))
                },
            )
            .firstOrNull()
    }

    private fun scoreCommentBoxCandidate(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        target: AccessibilityNodeInfo,
    ): Int {
        val rect = NodeUtils.bounds(target)
        val label = NodeUtils.collectSubtreeText(target, maxDepth = 2, maxNodes = 24)
        val resourceId = NodeUtils.resourceIdName(target)
        val className = NodeUtils.className(target)
        var score = 0

        if (matchesAny(label, COMMENT_INPUT_MARKERS)) score += 7
        if (resourceId.contains("comment", ignoreCase = true) || resourceId.contains("input", ignoreCase = true)) {
            score += 5
        }
        if (className.contains("EditText", ignoreCase = true)) score += 4
        if (NodeUtils.findClickable(target) != null || target.isEditable) score += 2
        if (rect.width() > cardBounds.width() * 0.34f) score += 2
        if (rect.centerX() > cardBounds.left + (cardBounds.width() * 0.42f).toInt()) score += 1
        if (findAvatarCompanion(cardRoot, cardBounds, rect) != null) score += 5
        return score
    }

    private fun findBottomAvatarNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        commentBox: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val commentBounds = commentBox?.let(NodeUtils::bounds) ?: return null
        return findAvatarCompanion(cardRoot, cardBounds, commentBounds)
    }

    private fun findAvatarCompanion(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        rowBounds: Rect,
    ): AccessibilityNodeInfo? {
        val minAvatarSize = maxOf((cardBounds.width() * 0.035f).toInt(), 18)
        val maxAvatarWidth = maxOf((cardBounds.width() * 0.16f).toInt(), minAvatarSize)
        val maxAvatarHeight = maxOf((rowBounds.height() * 2.0f).toInt(), maxAvatarWidth)

        return NodeUtils.flatten(cardRoot)
            .filter { node -> node.isVisibleToUser }
            .filter { node ->
                val rect = NodeUtils.bounds(node)
                if (rect.isEmpty) return@filter false
                if (rect.right > rowBounds.left + rowBounds.height() / 2) return@filter false
                if (rect.centerY() < rowBounds.top - rowBounds.height() / 2) return@filter false
                if (rect.centerY() > rowBounds.bottom + rowBounds.height() / 2) return@filter false
                if (rect.width() < minAvatarSize || rect.height() < minAvatarSize) return@filter false
                if (rect.width() > maxAvatarWidth || rect.height() > maxAvatarHeight) return@filter false

                val aspect = maxOf(rect.width(), rect.height()).toFloat() /
                    minOf(rect.width(), rect.height()).coerceAtLeast(1)
                if (aspect > 1.65f) return@filter false

                val label = NodeUtils.semanticLabel(node)
                if (matchesAny(label, TOP_MENU_MARKERS)) return@filter false
                if (matchesAny(label, ACTION_MARKERS)) return@filter false
                true
            }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { node ->
                    scoreAvatarCandidate(node)
                }.thenByDescending { node ->
                    NodeUtils.bounds(node).right
                },
            )
            .firstOrNull()
    }

    private fun scoreAvatarCandidate(node: AccessibilityNodeInfo): Int {
        val resourceId = NodeUtils.resourceIdName(node)
        val className = NodeUtils.className(node)
        val semantic = NodeUtils.semanticLabel(node)
        var score = 0

        if (matchesAny(resourceId, AVATAR_RESOURCE_HINTS)) score += 6
        if (matchesAny(semantic, AVATAR_RESOURCE_HINTS)) score += 4
        if (className.contains("Image", ignoreCase = true)) score += 3
        if (NodeUtils.combinedLabel(node).isBlank()) score += 1
        return score
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
        bottomAvatar: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        findExplicitLikeNode(cardRoot, cardBounds)?.let { return it }
        findActionRowLikeNode(cardRoot, cardBounds, actionAnchors)?.let { return it }
        return findBottomRowLikeNode(cardRoot, cardBounds, commentBox, topMenu, bottomAvatar)
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

                val target = compactTapTarget(node)
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty) return@mapNotNull null
                if (rect.centerY() < minActionY) return@mapNotNull null
                if (rect.width() > cardBounds.width() * 0.30f) return@mapNotNull null
                if (rect.height() > cardBounds.height() * 0.22f) return@mapNotNull null
                target
            }
            .distinctBy(::nodeIdentity)
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { target ->
                    scoreLikeCandidate(cardBounds, target)
                }.thenBy { target ->
                    abs(NodeUtils.bounds(target).centerY() - cardBounds.bottom)
                }.thenByDescending { target ->
                    NodeUtils.bounds(target).centerX()
                },
            )
            .firstOrNull()
    }

    private fun findActionRowLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        actionAnchors: List<AccessibilityNodeInfo>,
    ): AccessibilityNodeInfo? {
        if (actionAnchors.isEmpty()) return null

        val rowCenterY = actionAnchors
            .map { anchor -> NodeUtils.bounds(anchor).centerY() }
            .average()
            .toInt()
        val leftmostLabeledX = actionAnchors.minOf { anchor -> NodeUtils.bounds(anchor).left }

        return NodeUtils.flatten(cardRoot)
            .filter { node -> node.isVisibleToUser }
            .map(::compactTapTarget)
            .distinctBy(::nodeIdentity)
            .filter { target ->
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty) return@filter false
                if (rect.centerY() < cardBounds.top + cardBounds.height() * 0.45f) return@filter false
                if (abs(rect.centerY() - rowCenterY) > maxOf((cardBounds.height() * 0.12f).toInt(), 54)) return@filter false
                if (rect.centerX() >= leftmostLabeledX) return@filter false
                if (rect.centerX() < cardBounds.left + (cardBounds.width() * 0.48f).toInt()) return@filter false
                if (rect.width() > cardBounds.width() * 0.18f) return@filter false
                if (rect.height() > cardBounds.height() * 0.22f) return@filter false

                val label = NodeUtils.semanticLabel(target)
                if (matchesAny(label, ACTION_MARKERS)) return@filter false
                if (matchesAny(label, COMMENT_INPUT_MARKERS)) return@filter false
                if (matchesAny(label, NEGATIVE_KEYWORDS)) return@filter false
                true
            }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { target ->
                    scoreLikeCandidate(cardBounds, target)
                }.thenByDescending { target ->
                    NodeUtils.bounds(target).centerX()
                }.thenBy { target ->
                    abs(NodeUtils.bounds(target).centerY() - rowCenterY)
                },
            )
            .firstOrNull()
    }

    private fun findBottomRowLikeNode(
        cardRoot: AccessibilityNodeInfo,
        cardBounds: Rect,
        commentBox: AccessibilityNodeInfo?,
        topMenu: AccessibilityNodeInfo?,
        bottomAvatar: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val commentBounds = commentBox?.let(NodeUtils::bounds) ?: return null
        val menuIdentity = topMenu?.let(::nodeIdentity)
        val avatarCenterY = bottomAvatar?.let { avatar -> NodeUtils.bounds(avatar).centerY() }
        val rowCenterY = avatarCenterY?.let { (it + commentBounds.centerY()) / 2 } ?: commentBounds.centerY()
        val rowTolerance = maxOf(commentBounds.height(), 72)

        return NodeUtils.flatten(cardRoot)
            .filter { node -> node.isVisibleToUser }
            .map(::compactTapTarget)
            .distinctBy(::nodeIdentity)
            .filter { target ->
                val rect = NodeUtils.bounds(target)
                if (rect.isEmpty) return@filter false
                if (nodeIdentity(target) == menuIdentity) return@filter false
                if (rect.centerY() < rowCenterY - rowTolerance) return@filter false
                if (rect.centerY() > rowCenterY + rowTolerance) return@filter false
                if (rect.centerX() <= commentBounds.right) return@filter false
                if (rect.left < commentBounds.right - commentBounds.height() / 5) return@filter false
                if (rect.width() > cardBounds.width() * 0.18f) return@filter false
                if (rect.height() > maxOf((commentBounds.height() * 1.55f).toInt(), (cardBounds.height() * 0.22f).toInt())) {
                    return@filter false
                }

                val label = NodeUtils.semanticLabel(target)
                if (matchesAny(label, COMMENT_INPUT_MARKERS)) return@filter false
                if (matchesAny(label, TOP_MENU_MARKERS)) return@filter false
                if (matchesAny(label, ACTION_MARKERS)) return@filter false
                if (matchesAny(label, NEGATIVE_KEYWORDS)) return@filter false
                true
            }
            .sortedWith(
                compareByDescending<AccessibilityNodeInfo> { target ->
                    scoreLikeCandidate(cardBounds, target)
                }.thenByDescending { target ->
                    NodeUtils.bounds(target).centerX()
                }.thenBy { target ->
                    abs(NodeUtils.bounds(target).centerY() - rowCenterY)
                },
            )
            .firstOrNull()
    }

    private fun scoreLikeCandidate(
        cardBounds: Rect,
        target: AccessibilityNodeInfo,
    ): Int {
        val rect = NodeUtils.bounds(target)
        val label = NodeUtils.combinedLabel(target)
        val semantic = NodeUtils.semanticLabel(target)
        val resourceId = NodeUtils.resourceIdName(target)
        val className = NodeUtils.className(target)
        var score = 0

        if (matchesAny(label, LIKE_KEYWORDS)) score += 9
        if (matchesAny(semantic, LIKE_RESOURCE_HINTS)) score += 6
        if (matchesAny(resourceId, LIKE_RESOURCE_HINTS)) score += 4
        if (NodeUtils.findClickable(target) != null || target.isClickable) score += 3
        if (className.contains("Image", ignoreCase = true) || className.contains("Button", ignoreCase = true)) {
            score += 2
        }
        if (rect.width() <= cardBounds.width() * 0.16f) score += 1
        if (rect.centerX() > cardBounds.left + (cardBounds.width() * 0.62f).toInt()) score += 1
        return score
    }

    private fun compactTapTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val clickable = NodeUtils.findClickable(node) ?: return node
        val nodeRect = NodeUtils.bounds(node)
        val clickableRect = NodeUtils.bounds(clickable)
        if (nodeRect.isEmpty) return clickable
        if (clickableRect.isEmpty) return node

        val clickableArea = area(clickableRect)
        val nodeArea = area(nodeRect).coerceAtLeast(1L)
        return if (clickableArea <= nodeArea * 5L) clickable else node
    }

    private fun findLikeTapPoints(
        commentBox: AccessibilityNodeInfo?,
        bottomAvatar: AccessibilityNodeInfo?,
        actionAnchors: List<AccessibilityNodeInfo>,
        cardBounds: Rect,
    ): List<Point> {
        val points = mutableListOf<Point>()

        commentBox?.let { node ->
            val rect = NodeUtils.bounds(node)
            if (!rect.isEmpty) {
                val avatarCenterY = bottomAvatar?.let { avatar -> NodeUtils.bounds(avatar).centerY() }
                val rowCenterY = avatarCenterY?.let { (it + rect.centerY()) / 2 } ?: rect.centerY()
                val gutterLeft = maxOf(
                    rect.right + rect.height() / 6,
                    cardBounds.right - (cardBounds.width() * 0.24f).toInt(),
                )
                val gutterRight = cardBounds.right - maxOf((cardBounds.width() * 0.05f).toInt(), 12)
                if (gutterRight > gutterLeft) {
                    addTapPoint(points, (gutterLeft + gutterRight) / 2, rowCenterY, cardBounds)
                    addTapPoint(
                        points,
                        rect.right + ((gutterRight - rect.right) * 0.70f).toInt(),
                        rowCenterY,
                        cardBounds,
                    )
                }
            }
        }

        if (actionAnchors.isNotEmpty()) {
            val rowCenterY = actionAnchors
                .map { anchor -> NodeUtils.bounds(anchor).centerY() }
                .average()
                .toInt()
            val leftmostX = actionAnchors.minOf { anchor -> NodeUtils.bounds(anchor).left }
            addTapPoint(
                points,
                leftmostX - maxOf((cardBounds.width() * 0.09f).toInt(), 18),
                rowCenterY,
                cardBounds,
            )
            addTapPoint(
                points,
                leftmostX - maxOf((cardBounds.width() * 0.14f).toInt(), 28),
                rowCenterY,
                cardBounds,
            )
        }

        return points
    }

    private fun addTapPoint(
        points: MutableList<Point>,
        x: Int,
        y: Int,
        cardBounds: Rect,
    ) {
        if (x <= cardBounds.left || x >= cardBounds.right) return
        if (y <= cardBounds.top || y >= cardBounds.bottom) return
        if (points.any { point -> abs(point.x - x) < 18 && abs(point.y - y) < 18 }) return
        points += Point(x, y)
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
        private val LIKE_RESOURCE_HINTS = listOf("like", "zan", "praise", "digg", "thumb", "favor", "favour")
        private val ACTION_MARKERS = listOf("点赞", "已赞", "评论", "转发", "分享")
        private val COMMENT_INPUT_MARKERS = listOf("评论", "说点什么", "写评论", "输入", "回复", "发表评论", "写下评论")
        private val TOP_MENU_MARKERS = listOf("更多", "菜单", "更多操作", "更多选项")
        private val ALREADY_LIKED_KEYWORDS = listOf("已赞")
        private val NEGATIVE_KEYWORDS = listOf("取消赞", "收回赞", "赞了", "赞过", "赞同")
        private val AD_BADGE_KEYWORDS = listOf("广告", "推广", "赞助")
        private val AVATAR_RESOURCE_HINTS = listOf("avatar", "head", "profile", "user", "photo", "pic")
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
