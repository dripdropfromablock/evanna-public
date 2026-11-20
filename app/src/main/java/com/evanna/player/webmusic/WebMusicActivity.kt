package com.evanna.player.webmusic

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.evanna.player.R
import com.evanna.player.network.CancelScope
import com.evanna.player.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.HttpUrl
import java.util.concurrent.Executors

internal const val WEB_SEARCH_PAGE = "com.evanna.player.webmusic.SEARCH_PAGE"

@androidx.annotation.OptIn(UnstableApi::class)
class WebMusicActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private lateinit var browserPane: FrameLayout
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var secondary: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var openingPage: HttpUrl? = null
    private var active: CancelScope? = null
    private var playbackConnection: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inset = (12 * resources.displayMetrics.density).toInt()
        val preferences = getPreferences(MODE_PRIVATE)
        val adBlocker = WebAdBlocker(
            blockedHosts = {
                resources.openRawResource(R.raw.web_ad_hosts).bufferedReader().useLines { lines ->
                    lines.filterNot { it.isBlank() || it.startsWith('#') }.toHashSet()
                }
            },
            enabled = preferences.getBoolean("block_ads", true),
        )
        address = EditText(this).apply {
            hint = "Website or audio link"
            contentDescription = "Website or audio link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { openPage(); true } else false
            }
        }
        status = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = "Open a website or enter a direct audio link to play."
            setPadding(inset, 0, inset, 0)
        }
        play = button("Play") {
            val url = enteredAddress()
            if (url == null) status.text = "Enter a direct HTTPS audio link."
            else playLink(url)
        }
        secondary = button("Cancel") {
            active?.cancel()
            status.text = "Cancelling."
        }
        val browserNotice = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            text = "Opening browser."
        }
        browserPane = FrameLayout(this).apply { addView(browserNotice) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, windows ->
                val edges = windows.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(edges.left + inset, edges.top, edges.right + inset, edges.bottom)
                windows
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(address)
                addView(LinearLayout(context).apply {
                    addView(button("Open page") { openPage() })
                    addView(play)
                    addView(secondary)
                })
            })
            addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(CheckBox(context).apply {
                    text = "Block ads"
                    isChecked = adBlocker.enabled
                    setOnCheckedChangeListener { _, enabled ->
                        adBlocker.enabled = enabled
                        preferences.edit().putBoolean("block_ads", enabled).apply()
                        if (::browser.isInitialized && active == null && browser.url != null) browser.reload()
                    }
                })
            })
            addView(status)
            addView(browserPane, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    active != null -> { active?.cancel(); status.text = "Cancelling." }
                    playbackConnection != null -> Unit
                    ::browser.isInitialized && browser.canGoBack() -> browser.goBack()
                    else -> finish()
                }
            }
        })
        updateActions()
        status.isFocusableInTouchMode = true
        status.requestFocus()
        openingPage = (if (savedInstanceState == null) intent.getStringExtra(WEB_SEARCH_PAGE)
            else savedInstanceState.getString("openingPage"))?.let(::httpsWebAddress)

        val startup = WebViewStartUpConfig.Builder(Dispatchers.IO.asExecutor()).build()
        WebViewCompat.startUpWebView(applicationContext, startup,
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) {
                    if (isDestroyed || isFinishing) return
                    browser = createBrowser(adBlocker)
                    browserPane.removeAllViews()
                    browserPane.addView(browser)
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) browser.onPause()
                    openingPage?.let {
                        address.setText(it.toString())
                        browser.loadUrl(it.toString())
                    }
                    openingPage = null
                    updateActions()
                }

                override fun onError(error: WebViewStartupException) {
                    if (!isDestroyed && !isFinishing) browserNotice.text = "Could not start the in-app browser."
                }
            })
    }

    private fun createBrowser(adBlocker: WebAdBlocker) = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                httpsWebAddress(request.url.toString()) == null

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme == "http" ||
                    url.scheme == "https" && adBlocker.blocks(url.host, request.isForMainFrame)
                ) {
                    return WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("http:", ignoreCase = true)) {
                    view.stopLoading()
                    status.text = "The page switched to an insecure address."
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!address.hasFocus() && httpsWebAddress(url) != null) address.setText(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && active == null) {
                    status.text = "The page could not open."
                }
            }
        }
        setDownloadListener { _, _, _, _, _ ->
            status.text = "Open a direct audio stream to play it in Evanna."
        }
    }

    private fun button(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    private fun enteredAddress(): HttpUrl? = address.text.toString().trim().let {
        httpsWebAddress(if (it.isNotEmpty() && "://" !in it) "https://$it" else it)
    }

    private fun openPage() {
        if (!::browser.isInitialized) return
        val url = enteredAddress()
        if (url == null) {
            status.text = "Enter a valid HTTPS address."
            return
        }
        dismissKeyboard()
        browser.loadUrl(url.toString())
    }

    private fun dismissKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun playLink(url: HttpUrl) {
        if (!::browser.isInitialized || active != null || playbackConnection != null || isFinishing) return
        dismissKeyboard()
        address.setText(url.toString())
        val cancel = CancelScope()
        active = cancel
        status.text = "Checking whether this link can stream."
        updateActions()
        val name = URLUtil.guessFileName(url.toString(), null, null)
        val agent = browser.settings.userAgentString
        val sameOrigin = pageOwnsLink(url)
        worker.execute {
            val stream = runCatching {
                val cookie = if (sameOrigin) CookieManager.getInstance().getCookie(url.toString()) else null
                WebStreams.prepare(applicationContext, url, name, agent, cookie, cancel)
            }.getOrNull()
            runOnUiThread {
                active = null
                if (isDestroyed || isFinishing) return@runOnUiThread
                when {
                    cancel.cancelled -> {
                        status.text = "Playback cancelled."
                        updateActions()
                    }
                    stream == null -> {
                        status.text = "This link does not provide a seekable MP3 or FLAC stream."
                        updateActions()
                    }
                    else -> {
                        WebStreams.remember(stream)
                        val args = Bundle().apply { putString(PlaybackService.SOURCE_REF, stream.ref.encoded) }
                        openInPlayer(args)
                    }
                }
            }
        }
    }

    private fun openInPlayer(args: Bundle) {
        if (active != null || playbackConnection != null) return
        dismissKeyboard()
        if (::browser.isInitialized) browser.evaluateJavascript("document.querySelectorAll('audio,video').forEach(media => media.pause())", null)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val connection = MediaController.Builder(this, token).buildAsync()
        playbackConnection = connection
        status.text = "Opening in Evanna's player."
        updateActions()

        fun complete(success: Boolean) {
            if (playbackConnection !== connection) return
            playbackConnection = null
            MediaController.releaseFuture(connection)
            status.text = if (success) "Opened in Now Playing." else "Could not open this audio in the player."
            updateActions()
        }

        connection.addListener({
            if (playbackConnection === connection) {
                val request = runCatching {
                    connection.get().sendCustomCommand(SessionCommand(PlaybackService.PLAY_WEB_STREAM, Bundle.EMPTY), args)
                }.getOrNull()
                if (request == null) complete(false)
                else request.addListener({
                    complete(runCatching { request.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false))
                }, mainExecutor)
            }
        }, mainExecutor)
    }

    private fun pageOwnsLink(url: HttpUrl): Boolean =
        browser.url?.let(::httpsWebAddress)?.let { it.host == url.host && it.port == url.port } == true

    private fun updateActions() {
        address.isEnabled = ::browser.isInitialized
        play.isEnabled = ::browser.isInitialized && active == null && playbackConnection == null
        secondary.visibility = if (active != null) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("openingPage", openingPage?.toString())
    }

    override fun onPause() {
        if (::browser.isInitialized) browser.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized) browser.onResume()
    }

    override fun onDestroy() {
        active?.cancel()
        playbackConnection?.let(MediaController::releaseFuture)
        playbackConnection = null
        worker.shutdown()
        if (::browser.isInitialized) {
            browserPane.removeView(browser)
            browser.destroy()
        }
        super.onDestroy()
    }
}
