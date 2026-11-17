package com.example.shield

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : Activity() {

    private val home = "https://www.google.com"

    // Домены рекламы и трекеров. Список можно расширять.
    private val adDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "google-analytics.com", "adnxs.com", "taboola.com",
        "outbrain.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "adsrvr.org", "amazon-adsystem.com",
        "moatads.com", "scorecardresearch.com", "advertising.com", "adform.net",
        "casalemedia.com", "smartadserver.com", "teads.tv", "media.net",
        "zedo.com", "yieldmo.com", "sharethrough.com", "quantserve.com",
        "adsafeprotected.com", "serving-sys.com", "mgid.com", "revcontent.com",
        // Рунет
        "an.yandex.ru", "yandexadexchange.net", "adfox.ru", "adriver.ru",
        "ad.mail.ru", "mc.yandex.ru", "begun.ru", "marketgid.com", "tns-counter.ru"
    )

    // Скрывает пустые рекламные блоки
    private val hideJs = """
        (function(){
          var s=document.createElement('style');
          s.textContent='ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],' +
            'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],' +
            '.ad-banner,.ad-container,.ads,.advert,.advertisement,' +
            '[class*="adfox"],[id*="adfox"],[class*="banner-ad"]{display:none!important}';
          document.head.appendChild(s);
        })();
    """.trimIndent()

    private val tabs = mutableListOf<WebView>()
    private var current: WebView? = null
    private var enabled = true
    private var blocked = 0

    private lateinit var urlField: EditText
    private lateinit var shieldBtn: TextView
    private lateinit var tabsBtn: TextView
    private lateinit var content: FrameLayout
    private lateinit var progress: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        newTab(intent?.data?.toString() ?: home)
    }

    // ---------- Интерфейс ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Верхняя панель: адрес, щит, вкладки
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.WHITE)
        }

        urlField = EditText(this).apply {
            hint = "Введите запрос или адрес"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3F4"))
                cornerRadius = dp(20).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    current?.loadUrl(normalize(text.toString()))
                    clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
            setOnFocusChangeListener { _, focused -> if (focused) post { selectAll() } }
        }
        top.addView(urlField, LinearLayout.LayoutParams(0, dp(40), 1f))

        shieldBtn = barButton("🛡 0", 15f).apply {
            setTextColor(Color.parseColor("#188038"))
            setOnClickListener {
                enabled = !enabled
                updateShield()
                current?.reload()
            }
        }
        top.addView(shieldBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))

        tabsBtn = barButton("1", 15f).apply { setOnClickListener { showTabs() } }
        top.addView(tabsBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        content = FrameLayout(this)

        // Нижняя панель: назад, вперёд, обновить, новая вкладка
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        fun nav(label: String, action: () -> Unit) {
            val b = barButton(label, 22f).apply { setOnClickListener { action() } }
            bottom.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        nav("←") { current?.let { if (it.canGoBack()) it.goBack() } }
        nav("→") { current?.let { if (it.canGoForward()) it.goForward() } }
        nav("⟳") { current?.reload() }
        nav("+") { newTab(home) }

        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun barButton(label: String, size: Float) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#202124"))
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun updateShield() {
        shieldBtn.text = "🛡 $blocked"
        shieldBtn.setTextColor(Color.parseColor(if (enabled) "#188038" else "#9AA0A6"))
        shieldBtn.paint.isStrikeThruText = !enabled
        shieldBtn.invalidate()
    }

    // ---------- Вкладки ----------

    private fun newTab(url: String?): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (enabled && !request.isForMainFrame && isAd(request.url.host)) {
                    runOnUiThread { blocked++; updateShield() }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (enabled) view.evaluateJavascript(hideJs, null)
                if (view === current && !urlField.hasFocus()) urlField.setText(url ?: "")
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (view === current) {
                    progress.progress = p
                    progress.visibility = if (p < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                view.tag = title
            }

            // Всплывающие окна без нажатия пользователя (реклама) блокируются
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                if (!isUserGesture) return false
                val child = newTab(null)
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
        }

        tabs.add(wv)
        select(wv)
        url?.let { wv.loadUrl(it) }
        return wv
    }

    private fun select(wv: WebView) {
        current = wv
        content.removeAllViews()
        content.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        urlField.setText(wv.url ?: "")
        tabsBtn.text = tabs.size.toString()
    }

    private fun closeTab(wv: WebView) {
        tabs.remove(wv)
        content.removeAllViews()
        wv.destroy()
        if (tabs.isEmpty()) newTab(home) else select(tabs.last())
    }

    private fun showTabs() {
        val titles = tabs.map { (it.tag as? String)?.takeIf { t -> t.isNotBlank() } ?: it.url ?: "Новая вкладка" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Вкладки (${tabs.size})")
            .setItems(titles) { _, i -> select(tabs[i]) }
            .setPositiveButton("Новая вкладка") { _, _ -> newTab(home) }
            .setNeutralButton("Закрыть текущую") { _, _ -> current?.let { closeTab(it) } }
            .show()
    }

    // ---------- Вспомогательное ----------

    private fun isAd(host: String?): Boolean {
        if (host == null) return false
        val parts = host.split('.')
        for (i in 0 until parts.size - 1) {
            if (parts.subList(i, parts.size).joinToString(".") in adDomains) return true
        }
        return false
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (!t.contains(' ') && Regex("^[^\\s]+\\.[^\\s]{2,}$").matches(t)) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = current
        when {
            c != null && c.canGoBack() -> c.goBack()
            tabs.size > 1 && c != null -> closeTab(c)
            else -> super.onBackPressed()
        }
    }
}
