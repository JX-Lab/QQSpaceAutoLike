package io.github.yanganqi.qqspaceautolike.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GestureHelper(
    private val service: AccessibilityService,
    private val randomDelay: RandomDelay,
) {

    suspend fun tap(node: AccessibilityNodeInfo): Boolean {
        if (NodeUtils.click(node)) return true
        val rect = NodeUtils.bounds(node)
        if (rect.isEmpty) return false
        return performTap(rect.centerX(), rect.centerY())
    }

    suspend fun scrollDown(root: AccessibilityNodeInfo?): Boolean {
        if (NodeUtils.findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
            return true
        }

        val metrics = service.resources.displayMetrics
        val startX = metrics.widthPixels / 2 + randomDelay.jitter(36)
        val endX = startX + randomDelay.jitter(18)
        val startY = (metrics.heightPixels * 0.78f).toInt() + randomDelay.jitter(60)
        val endY = (metrics.heightPixels * 0.30f).toInt() + randomDelay.jitter(50)
        return performSwipe(startX, startY, endX, endY, randomDelay.scrollDurationMs())
    }

    private suspend fun performTap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        return dispatchGesture(gesture)
    }

    private suspend fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture)
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                },
                null,
            )
        }
    }
}

