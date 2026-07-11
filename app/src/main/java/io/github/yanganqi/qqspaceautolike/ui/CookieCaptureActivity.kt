package io.github.yanganqi.qqspaceautolike.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.yanganqi.qqspaceautolike.R
import io.github.yanganqi.qqspaceautolike.qzone.QzoneSession

class CookieCaptureActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var textStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cookie_capture)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarCookie)
        toolbar.setNavigationOnClickListener { finish() }

        textStatus = findViewById(R.id.textCookieCaptureStatus)
        webView = findViewById(R.id.webViewCookie)

        configureWebView()

        findViewById<MaterialButton>(R.id.btnCaptureCookieNow).setOnClickListener {
            captureCookieAndFinishIfPossible()
        }
        findViewById<MaterialButton>(R.id.btnReloadCookiePage).setOnClickListener {
            webView.reload()
        }

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                updateStatus(getString(R.string.cookie_capture_status_loading))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookie = collectCookie()
                val qq = QzoneSession.inferMyQq("", cookie)
                if (QzoneSession.computeGtk(cookie) != null) {
                    updateStatus(
                        getString(
                            R.string.cookie_capture_status_ready,
                            if (qq.isBlank()) getString(R.string.cookie_capture_unknown_qq) else qq,
                        ),
                    )
                } else {
                    updateStatus(getString(R.string.cookie_capture_status_waiting_login))
                }
            }
        }
    }

    private fun captureCookieAndFinishIfPossible() {
        val cookie = collectCookie()
        val normalized = QzoneSession.normalizeCookie(cookie)
        val myQq = QzoneSession.inferMyQq("", normalized)
        if (normalized.isBlank() || QzoneSession.computeGtk(normalized) == null) {
            Toast.makeText(this, R.string.cookie_capture_failed, Toast.LENGTH_SHORT).show()
            updateStatus(getString(R.string.cookie_capture_status_waiting_login))
            return
        }

        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_COOKIE, normalized)
                putExtra(EXTRA_MY_QQ, myQq)
            },
        )
        finish()
    }

    private fun collectCookie(): String {
        val cookieManager = CookieManager.getInstance()
        return listOf(
            "https://user.qzone.qq.com/",
            "https://h5.qzone.qq.com/",
            "https://qzone.qq.com/",
            "https://xui.ptlogin2.qq.com/",
        ).mapNotNull { url ->
            cookieManager.getCookie(url)?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString("; ")
    }

    private fun updateStatus(text: String) {
        textStatus.text = text
    }

    companion object {
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_MY_QQ = "my_qq"

        private const val START_URL = "https://user.qzone.qq.com/"
    }
}
