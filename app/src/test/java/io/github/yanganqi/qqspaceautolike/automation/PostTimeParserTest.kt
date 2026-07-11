package io.github.yanganqi.qqspaceautolike.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class PostTimeParserTest {

    private val parser = PostTimeParser(
        clock = Clock.fixed(
            Instant.parse("2026-07-10T04:00:00Z"),
            ZoneId.of("Asia/Shanghai"),
        ),
    )

    @Test
    fun `relative time labels resolve to expected ages`() {
        assertEquals(0L, parser.inferAgeDays("刚刚"))
        assertEquals(0L, parser.inferAgeDays("今天 12:34"))
        assertEquals(1L, parser.inferAgeDays("昨天 23:59"))
        assertEquals(2L, parser.inferAgeDays("前天 00:10"))
        assertEquals(5L, parser.inferAgeDays("5天前"))
    }

    @Test
    fun `calendar date labels use system clock date`() {
        assertEquals(0L, parser.inferAgeDays("7月10日 08:01"))
        assertEquals(1L, parser.inferAgeDays("7/9"))
        assertEquals(9L, parser.inferAgeDays("2026-07-01"))
        assertEquals(1L, parser.inferAgeDays("2026/07/09 18:20"))
    }

    @Test
    fun `timestamp detection ignores unrelated numeric labels`() {
        assertTrue(parser.looksLikeTimestamp("今天 12:34"))
        assertTrue(parser.looksLikeTimestamp("2026-07-01"))
        assertFalse(parser.looksLikeTimestamp("18岁"))
        assertFalse(parser.looksLikeTimestamp("点赞"))
    }
}
