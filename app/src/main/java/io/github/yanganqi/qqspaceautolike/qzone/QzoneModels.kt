package io.github.yanganqi.qqspaceautolike.qzone

data class QzoneFeedItem(
    val key: String,
    val hostUin: String,
    val fid: String,
    val discoveredAt: Long,
)

data class PendingLike(
    val key: String,
    val hostUin: String,
    val fid: String,
    val discoveredAt: Long,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
)

data class FeedPollResult(
    val items: List<QzoneFeedItem>,
    val responseCode: Int,
    val cookieInvalid: Boolean,
    val rawPreview: String,
    val skippedAdvertisements: Int,
    val skippedSelfPosts: Int,
)

data class LikeResponse(
    val success: Boolean,
    val cookieInvalid: Boolean,
    val code: Int?,
    val message: String,
    val rawPreview: String,
)

data class QueueUpdate(
    val addedCount: Int,
    val pendingCount: Int,
    val droppedExpiredPendingCount: Int,
    val droppedExpiredHistoryCount: Int,
)
