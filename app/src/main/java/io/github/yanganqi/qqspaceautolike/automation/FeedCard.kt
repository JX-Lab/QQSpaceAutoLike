package io.github.yanganqi.qqspaceautolike.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class FeedCard(
    val root: AccessibilityNodeInfo,
    val key: String,
    val bounds: Rect,
    val likeNode: AccessibilityNodeInfo?,
    val ageDays: Long?,
    val isAdvertisement: Boolean,
    val isAlreadyLiked: Boolean,
    val hasActionRow: Boolean,
)
