package io.github.yanganqi.qqspaceautolike.qzone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class LikeQueueStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pendingCount(retentionHours: Int): Int {
        return synchronized(lock) {
            val state = loadState()
            val pruned = pruneState(state, retentionHours)
            saveState(pruned)
            pruned.pending.size
        }
    }

    fun enqueue(
        items: List<QzoneFeedItem>,
        retentionHours: Int,
    ): QueueUpdate {
        return synchronized(lock) {
            val state = pruneState(loadState(), retentionHours)
            val now = System.currentTimeMillis()
            val pendingByKey = state.pending.associateBy(PendingLike::key).toMutableMap()
            val history = state.completed.toMutableMap()
            var addedCount = 0

            items.forEach { item ->
                if (history.containsKey(item.key)) return@forEach
                if (pendingByKey.containsKey(item.key)) return@forEach
                pendingByKey[item.key] = PendingLike(
                    key = item.key,
                    hostUin = item.hostUin,
                    fid = item.fid,
                    discoveredAt = item.discoveredAt.takeIf { it > 0 } ?: now,
                )
                addedCount += 1
            }

            val updated = QueueState(
                pending = pendingByKey.values.sortedBy(PendingLike::discoveredAt),
                completed = history,
            )
            saveState(updated)
            QueueUpdate(
                addedCount = addedCount,
                pendingCount = updated.pending.size,
                droppedExpiredPendingCount = state.expiredPendingCount,
                droppedExpiredHistoryCount = state.expiredHistoryCount,
            )
        }
    }

    fun peekEligible(
        minLikeAgeMinutes: Int,
        maxLikesPerSession: Int,
        retentionHours: Int,
    ): List<PendingLike> {
        return synchronized(lock) {
            val state = pruneState(loadState(), retentionHours)
            saveState(state)
            val minAgeMs = minLikeAgeMinutes.coerceIn(1, 24 * 60) * 60_000L
            val now = System.currentTimeMillis()
            state.pending
                .filter { item -> now - item.discoveredAt >= minAgeMs }
                .sortedBy(PendingLike::discoveredAt)
                .take(maxLikesPerSession.coerceIn(1, 50))
        }
    }

    fun inspectPendingWindow(
        minLikeAgeMinutes: Int,
        retentionHours: Int,
    ): PendingWindowStatus {
        return synchronized(lock) {
            val state = pruneState(loadState(), retentionHours)
            saveState(state)
            val minAgeMs = minLikeAgeMinutes.coerceIn(1, 24 * 60) * 60_000L
            val now = System.currentTimeMillis()

            var eligibleCount = 0
            var shortestWaitMs: Long? = null

            state.pending.forEach { item ->
                val remainingMs = minAgeMs - (now - item.discoveredAt)
                if (remainingMs <= 0L) {
                    eligibleCount += 1
                } else {
                    val currentShortestWaitMs = shortestWaitMs
                    if (currentShortestWaitMs == null || remainingMs < currentShortestWaitMs) {
                        shortestWaitMs = remainingMs
                    }
                }
            }

            PendingWindowStatus(
                pendingCount = state.pending.size,
                eligibleCount = eligibleCount,
                shortestWaitMinutes = shortestWaitMs?.let { waitMs ->
                    ((waitMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                },
            )
        }
    }

    fun markSuccess(
        item: PendingLike,
        retentionHours: Int,
    ): Int {
        return synchronized(lock) {
            val state = pruneState(loadState(), retentionHours)
            val updatedPending = state.pending.filterNot { pending -> pending.key == item.key }
            val updatedCompleted = state.completed.toMutableMap().apply {
                put(item.key, System.currentTimeMillis())
            }
            saveState(QueueState(updatedPending, updatedCompleted))
            updatedPending.size
        }
    }

    fun markFailure(
        item: PendingLike,
        retentionHours: Int,
        abandonAfterAttempts: Int = DEFAULT_ABANDON_ATTEMPTS,
    ): Int {
        return synchronized(lock) {
            val state = pruneState(loadState(), retentionHours)
            val updatedPending = state.pending.mapNotNull { pending ->
                if (pending.key != item.key) return@mapNotNull pending
                val nextAttemptCount = pending.attemptCount + 1
                if (nextAttemptCount >= abandonAfterAttempts.coerceAtLeast(1)) {
                    null
                } else {
                    pending.copy(
                        attemptCount = nextAttemptCount,
                        lastAttemptAt = System.currentTimeMillis(),
                    )
                }
            }
            val updatedCompleted = state.completed.toMutableMap()
            if (state.pending.any { pending -> pending.key == item.key } &&
                updatedPending.none { pending -> pending.key == item.key }
            ) {
                updatedCompleted[item.key] = System.currentTimeMillis()
            }
            saveState(QueueState(updatedPending, updatedCompleted))
            updatedPending.size
        }
    }

    private fun loadState(): QueueState {
        val pending = parsePending(preferences.getString(KEY_PENDING, null))
        val completed = parseCompleted(preferences.getString(KEY_COMPLETED, null))
        return QueueState(pending, completed)
    }

    private fun saveState(state: QueueState) {
        preferences.edit()
            .putString(KEY_PENDING, pendingToJson(state.pending).toString())
            .putString(KEY_COMPLETED, completedToJson(state.completed).toString())
            .apply()
    }

    private fun pruneState(
        state: QueueState,
        retentionHours: Int,
    ): QueueState {
        val retentionMs = retentionHours.coerceIn(1, 7 * 24) * 3_600_000L
        val now = System.currentTimeMillis()

        val freshPending = state.pending.filter { pending ->
            now - pending.discoveredAt <= retentionMs
        }
        val freshCompleted = state.completed.filterValues { handledAt ->
            now - handledAt <= retentionMs
        }

        return QueueState(
            pending = freshPending,
            completed = freshCompleted,
            expiredPendingCount = state.pending.size - freshPending.size,
            expiredHistoryCount = state.completed.size - freshCompleted.size,
        )
    }

    private fun parsePending(rawJson: String?): List<PendingLike> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val hostUin = item.optString("host_uin").trim()
                    val fid = item.optString("fid").trim()
                    val discoveredAt = item.optLong("discovered_at")
                    if (key.isBlank() || hostUin.isBlank() || fid.isBlank() || discoveredAt <= 0L) continue
                    add(
                        PendingLike(
                            key = key,
                            hostUin = hostUin,
                            fid = fid,
                            discoveredAt = discoveredAt,
                            attemptCount = item.optInt("attempt_count", 0),
                            lastAttemptAt = item.takeIf { obj -> obj.has("last_attempt_at") }
                                ?.optLong("last_attempt_at")
                                ?.takeIf { value -> value > 0L },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseCompleted(rawJson: String?): Map<String, Long> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val jsonObject = JSONObject(rawJson)
            buildMap {
                jsonObject.keys().forEach { key ->
                    val handledAt = jsonObject.optLong(key)
                    if (handledAt > 0L) {
                        put(key, handledAt)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun pendingToJson(pending: List<PendingLike>): JSONArray {
        return JSONArray().apply {
            pending.forEach { item ->
                put(
                    JSONObject()
                        .put("key", item.key)
                        .put("host_uin", item.hostUin)
                        .put("fid", item.fid)
                        .put("discovered_at", item.discoveredAt)
                        .put("attempt_count", item.attemptCount)
                        .put("last_attempt_at", item.lastAttemptAt ?: JSONObject.NULL),
                )
            }
        }
    }

    private fun completedToJson(completed: Map<String, Long>): JSONObject {
        return JSONObject().apply {
            completed.forEach { (key, handledAt) ->
                put(key, handledAt)
            }
        }
    }

    private data class QueueState(
        val pending: List<PendingLike>,
        val completed: Map<String, Long>,
        val expiredPendingCount: Int = 0,
        val expiredHistoryCount: Int = 0,
    )

    companion object {
        private const val PREFS_NAME = "qq_space_auto_like_queue"
        private const val KEY_PENDING = "pending"
        private const val KEY_COMPLETED = "completed"
        private const val DEFAULT_ABANDON_ATTEMPTS = 3

        private val lock = Any()
    }
}

data class PendingWindowStatus(
    val pendingCount: Int,
    val eligibleCount: Int,
    val shortestWaitMinutes: Int?,
)
