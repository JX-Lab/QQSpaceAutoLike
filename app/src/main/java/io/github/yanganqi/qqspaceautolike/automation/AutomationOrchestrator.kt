package io.github.yanganqi.qqspaceautolike.automation

import io.github.yanganqi.qqspaceautolike.config.AppConfig
import io.github.yanganqi.qqspaceautolike.qzone.LikeQueueStore
import io.github.yanganqi.qqspaceautolike.qzone.PendingLike
import io.github.yanganqi.qqspaceautolike.qzone.QzoneApiClient
import io.github.yanganqi.qqspaceautolike.qzone.QzoneSession
import io.github.yanganqi.qqspaceautolike.service.QqAutoLikeService

class AutomationOrchestrator(
    private val service: QqAutoLikeService,
    private val config: AppConfig,
    private val stopRequested: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {

    private val randomDelay = RandomDelay(config.randomDelay)
    private val queueStore = LikeQueueStore(service)

    suspend fun run(): ScanSummary {
        onStatus("等待手机 QQ 前台")
        if (!waitForQqForeground()) {
            return ScanSummary(0, 0, "未能进入手机 QQ 前台")
        }

        val normalizedCookie = QzoneSession.normalizeCookie(config.qzoneCookie)
        val myQq = QzoneSession.inferMyQq(config.myQq, normalizedCookie)
        if (myQq.isBlank() || normalizedCookie.isBlank()) {
            return ScanSummary(0, 0, "请先填写 QQ 号和 Qzone Cookie")
        }

        val client = runCatching {
            QzoneApiClient(myQq = myQq, rawCookie = normalizedCookie)
        }.getOrElse { error ->
            return ScanSummary(0, 0, error.message ?: "Qzone 配置无效")
        }

        onStatus("正在后台检查可补赞队列")
        val pollResult = client.fetchRecentMoodFeeds(skipAds = config.skipAds)
        if (pollResult.cookieInvalid) {
            return ScanSummary(0, 0, "Qzone Cookie 可能已失效，请重新抓取")
        }

        val queueUpdate = queueStore.enqueue(
            items = pollResult.items,
            retentionHours = config.queueRetentionHours,
        )
        val totalPending = queueUpdate.pendingCount
        if (totalPending == 0) {
            return ScanSummary(0, 0, "当前没有待补赞动态")
        }

        val eligible = queueStore.peekEligible(
            minLikeAgeMinutes = config.minLikeAgeMinutes,
            maxLikesPerSession = config.maxLikesPerSession,
            retentionHours = config.queueRetentionHours,
        )
        if (eligible.isEmpty()) {
            val window = queueStore.inspectPendingWindow(
                minLikeAgeMinutes = config.minLikeAgeMinutes,
                retentionHours = config.queueRetentionHours,
            )
            val reason = buildString {
                append("待补赞 ")
                append(window.pendingCount)
                append(" 条，但都还在延迟窗口（最短延迟 ")
                append(config.minLikeAgeMinutes)
                append(" 分钟")
                window.shortestWaitMinutes?.let { waitMinutes ->
                    append("，最早还需 ")
                    append(waitMinutes)
                    append(" 分钟")
                }
                append("），本次未发起点赞")
            }
            return ScanSummary(0, 0, reason)
        }

        val deadline = config.effectiveRunMinutes()?.let { System.currentTimeMillis() + it * 60_000L }
        var likes = 0

        eligible.forEachIndexed { index, item ->
            if (stopRequested()) {
                return ScanSummary(likes, 0, "任务已被停止")
            }
            if (deadline != null && System.currentTimeMillis() >= deadline) {
                return ScanSummary(likes, 0, "达到设定运行时长")
            }

            onStatus("准备补赞 ${index + 1}/${eligible.size}")
            pauseBeforeLike(index)

            val response = client.likeMood(item)
            if (response.cookieInvalid) {
                return ScanSummary(likes, 0, "Qzone Cookie 可能已失效，请重新抓取")
            }

            if (response.success) {
                likes += 1
                val remaining = queueStore.markSuccess(item, config.queueRetentionHours)
                onStatus("已补赞 $likes 条，待处理 $remaining 条")
                randomDelay.pause(3_500, 9_000)
            } else {
                val remaining = queueStore.markFailure(item, config.queueRetentionHours)
                onStatus("补赞失败，剩余待处理 $remaining 条")
                randomDelay.pause(1_600, 3_400)
            }
        }

        val remaining = queueStore.pendingCount(config.queueRetentionHours)
        return if (likes > 0) {
            ScanSummary(likes, 0, "本次前台会话已完成，剩余待处理 $remaining 条")
        } else {
            ScanSummary(0, 0, "本次没有补赞成功，剩余待处理 $remaining 条")
        }
    }

    suspend fun pollPendingQueue(): String {
        val normalizedCookie = QzoneSession.normalizeCookie(config.qzoneCookie)
        val myQq = QzoneSession.inferMyQq(config.myQq, normalizedCookie)
        if (myQq.isBlank() || normalizedCookie.isBlank()) {
            return "后台侦测已暂停：请填写 QQ 号和 Qzone Cookie"
        }

        val client = runCatching {
            QzoneApiClient(myQq = myQq, rawCookie = normalizedCookie)
        }.getOrElse { error ->
            return "后台侦测已暂停：${error.message ?: "Qzone 配置无效"}"
        }

        val pollResult = client.fetchRecentMoodFeeds(skipAds = config.skipAds)
        if (pollResult.cookieInvalid) {
            return "后台侦测已暂停：Qzone Cookie 可能已失效"
        }

        val queueUpdate = queueStore.enqueue(
            items = pollResult.items,
            retentionHours = config.queueRetentionHours,
        )
        return buildString {
            append("后台已侦测：新增 ")
            append(queueUpdate.addedCount)
            append(" 条，待补赞 ")
            append(queueUpdate.pendingCount)
            append(" 条，等待你打开手机 QQ")
            if (pollResult.skippedAdvertisements > 0) {
                append("；已跳过广告 ")
                append(pollResult.skippedAdvertisements)
                append(" 条")
            }
        }
    }

    private suspend fun pauseBeforeLike(index: Int) {
        if (index == 0) {
            randomDelay.pause(6_000, 16_000)
        } else {
            randomDelay.pause(8_000, 20_000)
        }
    }

    private suspend fun waitForQqForeground(): Boolean {
        repeat(40) {
            if (stopRequested()) return false
            val packageName = service.rootInActiveWindow?.packageName?.toString()
                ?: service.currentObservedPackage()
            if (packageName == QqAutoLikeService.QQ_PACKAGE_NAME) {
                return true
            }
            randomDelay.shortWait()
        }
        return false
    }
}
