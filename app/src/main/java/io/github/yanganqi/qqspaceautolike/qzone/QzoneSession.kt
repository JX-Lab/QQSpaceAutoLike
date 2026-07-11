package io.github.yanganqi.qqspaceautolike.qzone

object QzoneSession {

    fun normalizeCookie(rawCookie: String): String {
        val normalized = rawCookie
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .replace("\n", ";")
            .replace("\r", ";")
            .split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                trimmed.takeIf { it.isNotEmpty() }
            }
            .distinct()
        return normalized.joinToString("; ")
    }

    fun extractCookieValue(cookie: String, key: String): String {
        return cookie.split(';')
            .map { it.trim() }
            .firstOrNull { entry -> entry.startsWith("$key=") }
            ?.substringAfter('=')
            .orEmpty()
    }

    fun inferMyQq(configuredQq: String, cookie: String): String {
        val explicit = configuredQq.trim().filter(Char::isDigit)
        if (explicit.isNotEmpty()) return explicit

        return sequenceOf("uin", "p_uin", "ptui_loginuin")
            .map { key -> extractCookieValue(cookie, key) }
            .map { value -> value.trim().removePrefix("o").filter(Char::isDigit) }
            .firstOrNull { value -> value.isNotEmpty() }
            .orEmpty()
    }

    fun computeGtk(cookie: String): Int? {
        val skey = sequenceOf("p_skey", "skey", "media_p_skey")
            .map { key -> extractCookieValue(cookie, key) }
            .firstOrNull { value -> value.isNotEmpty() }
            ?: return null

        var hash = 5381
        skey.forEach { ch ->
            hash += (hash shl 5) + ch.code
        }
        return hash and 0x7FFFFFFF
    }
}
