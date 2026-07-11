package io.github.yanganqi.qqspaceautolike.qzone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QzoneSessionTest {

    @Test
    fun normalizeCookieStripsPrefixAndLineBreaks() {
        val cookie = """
            cookie: uin=o123456;
            p_skey=abc123
        """.trimIndent()

        val normalized = QzoneSession.normalizeCookie(cookie)

        assertTrue(normalized.contains("uin=o123456"))
        assertTrue(normalized.contains("p_skey=abc123"))
        assertTrue(!normalized.contains("cookie:"))
    }

    @Test
    fun inferMyQqFallsBackToCookie() {
        val qq = QzoneSession.inferMyQq(
            configuredQq = "",
            cookie = "uin=o00123456; p_skey=test",
        )

        assertEquals("00123456", qq)
    }

    @Test
    fun computeGtkUsesPKeyFamily() {
        val gtk = QzoneSession.computeGtk("uin=o123; p_skey=test")

        assertEquals(2090756197, gtk)
    }
}
