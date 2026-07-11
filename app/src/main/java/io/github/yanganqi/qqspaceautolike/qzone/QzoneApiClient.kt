package io.github.yanganqi.qqspaceautolike.qzone

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

class QzoneApiClient(
    private val myQq: String,
    rawCookie: String,
) {

    private val cookie = QzoneSession.normalizeCookie(rawCookie)
    private val gtk = QzoneSession.computeGtk(cookie)
        ?: error("cookie 缺少 p_skey/skey，无法计算 g_tk")

    fun fetchRecentMoodFeeds(
        skipAds: Boolean,
        count: Int = DEFAULT_FEED_COUNT,
    ): FeedPollResult {
        val requestUrl = buildString {
            append("https://user.qzone.qq.com/proxy/domain/ic2.qzone.qq.com/cgi-bin/feeds/feeds3_html_more")
            append("?uin=").append(myQq)
            append("&scope=0&view=1&flag=1&refresh=1")
            append("&count=").append(count.coerceIn(10, 60))
            append("&outputhtmlfeed=1&useutf8=1")
            append("&g_tk=").append(gtk)
            append("&r=").append(Random.nextDouble())
        }

        val response = executeGet(requestUrl)
        val body = response.body
        val cookieInvalid = looksLikeCookieInvalid(response.code, body)

        val items = mutableListOf<QzoneFeedItem>()
        var skippedAdvertisements = 0
        var skippedSelfPosts = 0

        FEED_KEY_REGEX.findAll(body).forEach { match ->
            val hostUin = match.groupValues[1]
            val fid = match.groupValues[2]
            val discoveredAt = System.currentTimeMillis()
            val start = (match.range.first - 320).coerceAtLeast(0)
            val end = (match.range.last + 320).coerceAtMost(body.lastIndex)
            val context = body.substring(start, end + 1)

            if (hostUin == myQq) {
                skippedSelfPosts += 1
                return@forEach
            }
            if (skipAds && AD_MARKERS.any { marker -> context.contains(marker, ignoreCase = true) }) {
                skippedAdvertisements += 1
                return@forEach
            }

            items += QzoneFeedItem(
                key = "https://user.qzone.qq.com/$hostUin/mood/$fid.1",
                hostUin = hostUin,
                fid = fid,
                discoveredAt = discoveredAt,
            )
        }

        return FeedPollResult(
            items = items.distinctBy(QzoneFeedItem::key),
            responseCode = response.code,
            cookieInvalid = cookieInvalid,
            rawPreview = body.take(280),
            skippedAdvertisements = skippedAdvertisements,
            skippedSelfPosts = skippedSelfPosts,
        )
    }

    fun likeMood(item: PendingLike): LikeResponse {
        val baseKey = "https://user.qzone.qq.com/${item.hostUin}/mood/${item.fid}"
        val payload = linkedMapOf(
            "qzreferrer" to "https://user.qzone.qq.com/$myQq",
            "opuin" to myQq,
            "unikey" to baseKey,
            "curkey" to baseKey,
            "appid" to "311",
            "from" to "1",
            "typeid" to "0",
            "abstime" to (System.currentTimeMillis() / 1000L).toString(),
            "fid" to item.fid,
            "active" to "0",
            "format" to "json",
            "fupdate" to "1",
        )

        val requestUrl = "https://user.qzone.qq.com/proxy/domain/w.qzone.qq.com/cgi-bin/likes/internal_dolike_app?g_tk=$gtk"
        val response = executePost(requestUrl, payload)
        val body = response.body
        val preview = body.take(280)
        val cookieInvalid = looksLikeCookieInvalid(response.code, body)
        val code = CODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val message = MESSAGE_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()

        return LikeResponse(
            success = code == 0,
            cookieInvalid = cookieInvalid,
            code = code,
            message = message,
            rawPreview = preview,
        )
    }

    private fun executeGet(url: String): RawHttpResponse {
        val connection = openConnection(url).apply {
            requestMethod = "GET"
        }
        return connection.readResponse()
    }

    private fun executePost(
        url: String,
        formBody: Map<String, String>,
    ): RawHttpResponse {
        val encoded = formBody.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
        val connection = openConnection(url).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        connection.outputStream.use { stream ->
            stream.write(bytes)
        }
        return connection.readResponse()
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Origin", "https://user.qzone.qq.com")
            setRequestProperty("Referer", "https://user.qzone.qq.com/$myQq")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
    }

    private fun HttpURLConnection.readResponse(): RawHttpResponse {
        return try {
            val responseCode = responseCode
            val stream = if (responseCode >= 400) errorStream else inputStream
            RawHttpResponse(responseCode, stream.readText())
        } finally {
            disconnect()
        }
    }

    private fun InputStream?.readText(): String {
        if (this == null) return ""
        return BufferedReader(InputStreamReader(this, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                var line: String? = reader.readLine()
                while (line != null) {
                    append(line)
                    append('\n')
                    line = reader.readLine()
                }
            }
        }
    }

    private fun looksLikeCookieInvalid(
        statusCode: Int,
        body: String,
    ): Boolean {
        if (statusCode in REDIRECT_CODES || statusCode == 401 || statusCode == 403) return true
        val normalized = body.take(4000).lowercase()
        if (normalized.contains("<html") || normalized.contains("<!doctype html")) {
            if (COOKIE_ERROR_MARKERS.any { marker -> normalized.contains(marker) }) return true
        }
        return COOKIE_ERROR_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private data class RawHttpResponse(
        val code: Int,
        val body: String,
    )

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }

    companion object {
        private const val DEFAULT_FEED_COUNT = 30
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val FEED_KEY_REGEX = Regex("""user\.qzone\.qq\.com[\\/]+(\d+)[\\/]+mood[\\/]+([a-f0-9]+)""")
        private val CODE_REGEX = Regex(""""code"\s*:\s*(-?\d+)""")
        private val MESSAGE_REGEX = Regex(""""message"\s*:\s*"([^"]*)"""")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val COOKIE_ERROR_MARKERS = listOf(
            "ptlogin",
            "pt_login",
            "未登录",
            "请先登录",
            "skey expired",
            "captcha",
            "验证",
            "安全",
            "login",
        )
        private val AD_MARKERS = listOf(
            "广告",
            "推广",
            "赞助",
            "品牌馆",
            "小世界",
            "热推",
            "直播",
            "小游戏",
            "小程序",
            "黄钻",
        )
    }
}
