package com.xs.chat.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * 内置浏览器（Via 简化版）：地址栏 + WebView，参考资料/搜索链接直接在 App 内打开，
 * 支持刷新、后退、前进、系统浏览器打开、页面内下载。
 */
class WebBrowserActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var urlInput: EditText? = null
    private var webProgress: ProgressBar? = null

    companion object {
        private const val EXTRA_URL = "url"

        fun open(context: Context, url: String) {
            if (!url.startsWith("http")) return
            context.startActivity(
                Intent(context, WebBrowserActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF7, 0xF7, 0xF5))
            setPadding(dp(6), statusBarHeight(), dp(6), 0)
        }
        root.addView(topbar())
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        root.addView(webProgress)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = settings.userAgentString.replace("; wv", "")
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString().orEmpty()
                    if (u.startsWith("http://") || u.startsWith("https://")) {
                        view?.loadUrl(u)
                    } else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                    }
                    return true
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
            urlInput?.setText(url ?: "")
            webProgress?.setVisibility(View.VISIBLE)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webProgress?.setVisibility(View.GONE)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) toast("页面加载失败，请检查网络")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    webProgress?.progress = newProgress
                }
            }
            setDownloadListener(DownloadListener { url, _, _, suggestion, _ ->
                val name = URLUtil.guessFileName(url, null, suggestion ?: "application/octet-stream")
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(
                    DownloadManager.Request(Uri.parse(url)).setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                )
                toast("开始下载：$name")
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(webView)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView ?: return
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })
        urlInput?.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { navigate(urlInput?.text?.toString().orEmpty()); true } else false
        }
        navigate(intent.getStringExtra(EXTRA_URL) ?: "https://www.bing.com")
    }

    private fun topbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(textBtn("◀") { onBackPressedDispatcher.onBackPressed() }.apply { contentDescription = "后退" })
        urlInput = EditText(this).apply {
            hint = "输入网址，回车打开"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
        }
        bar.addView(urlInput)
        bar.addView(textBtn("⟳") { webView?.reload() }.apply { contentDescription = "刷新" })
        bar.addView(textBtn("▶") { webView?.goForward() }.apply { contentDescription = "前进" })
        bar.addView(textBtn("↗") {
            webView?.url?.let { u -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
        }.apply { contentDescription = "系统浏览器打开" })
        return bar
    }

    private fun textBtn(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(0x33, 0x33, 0x33))
        setPadding(dp(5), dp(2), dp(5), dp(2))
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
    }

    private fun textBtn(label: String, action: () -> Unit) = textBtn(label).apply {
        setOnClickListener { action() }
    }

    private fun navigate(raw: String) {
        val u = URLUtil.guessUrl(raw.trim().ifEmpty { "https://www.bing.com" })
        webView?.loadUrl(u)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return dp(8) + (if (id > 0) resources.getDimensionPixelSize(id) else 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
