package io.github.yanganqi.qqspaceautolike.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object NodeUtils {

    fun flatten(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return result
    }

    fun findFirstByAnyText(
        root: AccessibilityNodeInfo?,
        candidates: Collection<String>,
        exact: Boolean,
    ): AccessibilityNodeInfo? {
        val normalized = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        return flatten(root).firstOrNull { node ->
            val ownText = combinedLabel(node)
            ownText.isNotBlank() && normalized.any { candidate ->
                if (exact) ownText == candidate else ownText.contains(candidate, ignoreCase = true)
            }
        }
    }

    fun hasAnyText(root: AccessibilityNodeInfo?, candidates: Collection<String>): Boolean {
        return flatten(root).any { node ->
            val label = combinedLabel(node)
            candidates.any { label.contains(it, ignoreCase = true) }
        }
    }

    fun allTexts(root: AccessibilityNodeInfo?): List<String> {
        return flatten(root)
            .mapNotNull { node ->
                combinedLabel(node).takeIf { it.isNotBlank() }
            }
    }

    fun combinedLabel(node: AccessibilityNodeInfo): String {
        val parts = buildList {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.joinToString(" ").trim()
    }

    fun collectContextText(node: AccessibilityNodeInfo, ancestorLevels: Int = 4): String {
        val builder = StringBuilder()
        var current: AccessibilityNodeInfo? = node
        repeat(ancestorLevels) {
            current ?: return@repeat
            builder.append(' ')
            builder.append(collectSubtreeText(current, maxDepth = 2, maxNodes = 24))
            current = current.parent
        }
        return builder.toString()
    }

    fun collectSubtreeText(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxNodes: Int,
    ): String {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val seen = mutableListOf<String>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && seen.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            combinedLabel(node).takeIf { it.isNotBlank() }?.let(seen::add)
            if (depth >= maxDepth) continue
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    queue.addLast(child to (depth + 1))
                }
            }
        }
        return seen.joinToString(" ")
    }

    fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(6) {
            if (current == null) return null
            if (current.isClickable && current.isVisibleToUser) return current
            current = current.parent
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo?): Boolean {
        return findClickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return flatten(root).firstOrNull { node ->
            node.isScrollable && node.isVisibleToUser
        }
    }

    fun bounds(node: AccessibilityNodeInfo): Rect {
        return Rect().also(node::getBoundsInScreen)
    }

    fun stableKey(node: AccessibilityNodeInfo): String {
        val rect = bounds(node)
        val context = collectContextText(node).take(80)
        return "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}|$context"
    }
}

