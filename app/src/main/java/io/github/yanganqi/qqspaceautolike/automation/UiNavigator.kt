package io.github.yanganqi.qqspaceautolike.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class UiNavigator(
    private val service: AccessibilityService,
    private val gestureHelper: GestureHelper,
    private val randomDelay: RandomDelay,
) {

    suspend fun openQqSpaceFeed(rootProvider: () -> AccessibilityNodeInfo?): Boolean {
        dismissCommonDialogs(rootProvider)
        if (isFeedVisible(rootProvider())) return true

        tryOpenFeedEntry(rootProvider)
        if (isFeedVisible(rootProvider())) return true

        repeat(18) {
            if (isFeedVisible(rootProvider())) return true
            dismissCommonDialogs(rootProvider)
            if (tryOpenFeedEntry(rootProvider)) {
                if (isFeedVisible(rootProvider())) return true
            } else {
                randomDelay.shortWait()
            }
        }
        return false
    }

    private suspend fun tryOpenFeedEntry(rootProvider: () -> AccessibilityNodeInfo?): Boolean {
        val clickedSpaceEntry = clickAnyText(rootProvider, SPACE_ENTRY_LABELS)
        if (clickedSpaceEntry) {
            randomDelay.afterNavigation()
            dismissCommonDialogs(rootProvider)
            return true
        }

        val clickedDynamicTab = clickAnyText(rootProvider, DYNAMIC_TAB_LABELS)
        if (clickedDynamicTab) {
            randomDelay.afterNavigation()
            dismissCommonDialogs(rootProvider)
            return true
        }

        return false
    }

    private suspend fun clickAnyText(
        rootProvider: () -> AccessibilityNodeInfo?,
        labels: Collection<String>,
    ): Boolean {
        val root = rootProvider() ?: return false
        listOf(true, false).forEach { exact ->
            val node = NodeUtils.findFirstByAnyText(root, labels, exact) ?: return@forEach
            if (gestureHelper.tap(node)) return true
        }
        return false
    }

    private fun isFeedVisible(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (NodeUtils.hasAnyText(root, listOf("好友动态", "空间动态"))) return true
        val likeNode = NodeUtils.findFirstByAnyText(root, LIKE_LABELS, exact = false)
        return likeNode != null && root.packageName?.toString() == service.rootInActiveWindow?.packageName?.toString()
    }

    private suspend fun dismissCommonDialogs(rootProvider: () -> AccessibilityNodeInfo?) {
        COMMON_DIALOG_ACTIONS.forEach { label ->
            val node = NodeUtils.findFirstByAnyText(rootProvider(), listOf(label), exact = true) ?: return@forEach
            gestureHelper.tap(node)
            randomDelay.shortWait()
        }
    }

    companion object {
        private val DYNAMIC_TAB_LABELS = listOf("动态")
        private val SPACE_ENTRY_LABELS = listOf("好友动态", "空间动态", "QQ空间")
        private val LIKE_LABELS = listOf("点赞", "赞", "已赞")
        private val COMMON_DIALOG_ACTIONS = listOf(
            "我知道了",
            "知道了",
            "允许",
            "仅本次允许",
            "关闭",
            "跳过",
            "稍后再说",
        )
    }
}
