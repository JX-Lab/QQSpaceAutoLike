package io.github.yanganqi.qqspaceautolike.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class UiNavigator(
    private val service: AccessibilityService,
    private val gestureHelper: GestureHelper,
    private val randomDelay: RandomDelay,
) {

    suspend fun openQqSpaceFeed(
        rootProvider: () -> AccessibilityNodeInfo?,
        onStatus: (String) -> Unit,
    ): Boolean {
        dismissCommonDialogs(rootProvider)
        if (isFeedVisible(rootProvider())) return true

        repeat(8) { attempt ->
            if (isFeedVisible(rootProvider())) return true
            dismissCommonDialogs(rootProvider)

            if (tryOpenSpaceEntry(rootProvider)) {
                onStatus("已点击好友动态入口，等待空间页加载")
                if (waitForFeedVisible(rootProvider)) {
                    return true
                }
            }

            if (tryOpenDynamicTab(rootProvider)) {
                onStatus("已进入 QQ 动态页，继续寻找好友动态入口")
                waitForUiTransition()
                dismissCommonDialogs(rootProvider)
                if (tryOpenSpaceEntry(rootProvider)) {
                    onStatus("已点击好友动态入口，等待空间页加载")
                    if (waitForFeedVisible(rootProvider)) {
                        return true
                    }
                }
            }

            if (attempt < 7) {
                randomDelay.shortWait()
            }
        }
        return false
    }

    private suspend fun tryOpenSpaceEntry(rootProvider: () -> AccessibilityNodeInfo?): Boolean {
        val clickedSpaceEntry = clickAnyText(rootProvider, SPACE_ENTRY_LABELS)
        if (clickedSpaceEntry) {
            dismissCommonDialogs(rootProvider)
            return true
        }
        return false
    }

    private suspend fun tryOpenDynamicTab(rootProvider: () -> AccessibilityNodeInfo?): Boolean {
        val clickedDynamicTab = clickAnyText(rootProvider, DYNAMIC_TAB_LABELS)
        if (clickedDynamicTab) {
            dismissCommonDialogs(rootProvider)
            return true
        }
        return false
    }

    private suspend fun waitForFeedVisible(rootProvider: () -> AccessibilityNodeInfo?): Boolean {
        repeat(12) {
            dismissCommonDialogs(rootProvider)
            if (isFeedVisible(rootProvider())) return true
            randomDelay.shortWait()
        }
        return false
    }

    private suspend fun waitForUiTransition() {
        randomDelay.pause(350, 700)
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
        val allTexts = NodeUtils.allTexts(root)
        val strongMarkerHits = FEED_PAGE_MARKERS.count { marker ->
            allTexts.any { text -> text.contains(marker, ignoreCase = true) }
        }
        if (strongMarkerHits >= 2) return true
        val likeNode = NodeUtils.findFirstByAnyText(root, LIKE_LABELS, exact = false)
        return strongMarkerHits >= 1 &&
            likeNode != null &&
            root.packageName?.toString() == service.rootInActiveWindow?.packageName?.toString()
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
        private val FEED_PAGE_MARKERS = listOf(
            "写说说",
            "说说",
            "相册",
            "留言",
            "个性化",
            "谁看过我",
        )
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
