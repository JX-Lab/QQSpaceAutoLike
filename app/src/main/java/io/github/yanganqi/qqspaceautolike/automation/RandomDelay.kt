package io.github.yanganqi.qqspaceautolike.automation

import kotlinx.coroutines.delay
import kotlin.random.Random

class RandomDelay(private val enabled: Boolean) {

    suspend fun afterNavigation() {
        pause(700, 1_300)
    }

    suspend fun betweenLikes() {
        pause(900, 2_200)
    }

    suspend fun afterScroll() {
        pause(850, 1_700)
    }

    suspend fun shortWait() {
        pause(280, 520)
    }

    suspend fun pause(minMs: Long, maxMs: Long) {
        val actual = if (enabled) {
            Random.nextLong(minMs, maxMs + 1)
        } else {
            (minMs + maxMs) / 2
        }
        delay(actual)
    }

    fun scrollDurationMs(): Long {
        return if (enabled) Random.nextLong(260, 480) else 340
    }

    fun jitter(range: Int): Int {
        return if (enabled) Random.nextInt(-range, range + 1) else 0
    }
}

