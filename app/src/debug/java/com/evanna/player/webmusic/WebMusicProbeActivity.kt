package com.evanna.player.webmusic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.isLocalTree
import com.evanna.player.network.CancelScope
import java.io.File
import java.io.DataInputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WebMusicProbeActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val journal = mutableListOf<String>()
    private var capture: CancelScope? = null
    private var retained: File? = null
    private var controlled = false
    internal var importFixture: File? = null
    internal val observations = LinkedBlockingQueue<CaptureObservation>()
    internal val finished = CompletableFuture<String>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        controlled = intent.getBooleanExtra("controlled", false)
        address = EditText(this).apply {
            hint = "Enter an HTTPS page"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        status = TextView(this).apply { text = "Open a source and choose a download to test." }
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !acceptedPage(request.url.toString())
            }
            setDownloadListener { url, agent, _, mime, _ ->
                onDownload(CaptureRequest(url, this.url.orEmpty(), agent, mime))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(address)
            addView(LinearLayout(context).apply {
                addView(button("Go") { open(address.text.toString().trim()) })
                addView(button("Cancel capture") { capture?.cancel() })
                addView(button("Finish test") { finish() })
            })
            addView(LinearLayout(context).apply {
                addView(button("Import test audio") { pickFolder() })
                addView(button("Results") {
                    AlertDialog.Builder(this@WebMusicProbeActivity).setTitle("Observed results")
                        .setMessage(journal.joinToString("\n").ifBlank { "No capture attempts." })
                        .setPositiveButton("Close", null).show()
                })
            })
            addView(status)
            addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun acceptedPage(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
            (url.isHttps || controlled && url.host == "127.0.0.1")
    }

    private fun open(value: String) {
        if (!acceptedPage(value)) {
            status.text = "Enter a valid HTTPS address."
            return
        }
        capture?.cancel()
        browser.loadUrl(value)
    }

    internal fun openFixture(url: String) {
        check(controlled)
        open(url)
    }

    private fun onDownload(request: CaptureRequest) {
        val scheme = request.url.substringBefore(':').lowercase()
        val host = request.url.toHttpUrlOrNull()?.host ?: browser.url?.toHttpUrlOrNull()?.host ?: "Page"
        record("$host · Download requested (${scheme.takeIf { it in listOf("http", "https", "blob", "data") } ?: "other"})")
        if (capture != null) {
            record("A capture is already running.")
            return
        }
        if (controlled || scheme == "blob" || scheme == "data") {
            startCapture(request)
        } else {
            AlertDialog.Builder(this).setTitle("Test this download?")
                .setMessage(host).setNegativeButton("Cancel", null)
                .setPositiveButton("Test") { _, _ -> startCapture(request) }.show()
        }
    }

    private fun startCapture(request: CaptureRequest) {
        if (capture != null || isFinishing) return
        val cancel = CancelScope()
        capture = cancel
        status.text = "Capturing and decoding…"
        worker.execute {
            val result = try {
                val cookie = request.url.toHttpUrlOrNull()?.let { CookieManager.getInstance().getCookie(request.url) }
                CaptureProbe.capture(applicationContext, request, cancel, controlled, cookie)
            } catch (_: Exception) {
                CaptureObservation("capture failed")
            }
            runOnUiThread {
                capture = null
                if (isFinishing || isDestroyed) {
                    result.file?.delete()
                    return@runOnUiThread
                }
                retained?.delete()
                retained = result.file
                record(result.summary())
                if (controlled) observations.offer(result)
            }
        }
    }

    private fun record(line: String) {
        status.text = line
        if (journal.size == 80) journal.removeAt(0)
        journal.add(line)
    }

    private fun pickFolder() {
        if (importFixture == null) return
        AlertDialog.Builder(this).setTitle("Import generated test audio?")
            .setMessage("Choose a dedicated empty local folder. The file will stay there and Evanna will scan the folder into its library.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose folder") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                ), FOLDER_REQUEST)
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FOLDER_REQUEST) return
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) {
            record("Import cancelled at folder selection.")
            return
        }
        if (!isLocalTree(this, tree)) {
            record("Choose a local music folder.")
            return
        }
        val fixture = importFixture ?: return
        worker.execute {
            val result = runCatching { importTestFile(tree, fixture) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isFailure) {
                    record("Import failed. Test audio is still available to retry.")
                    return@runOnUiThread
                }
                val documentId = result.getOrThrow()
                val alreadyRegistered = tree.toString() in LocalLibrary.database(this).rootUris()
                if (LocalLibrary.scanning || LocalLibrary.managingFolders) {
                    record("Test audio saved and verified. Scan is busy. Rescan this folder from Library.")
                } else {
                    LocalLibrary.scan(this, tree, add = !alreadyRegistered)
                    record("Test audio saved and verified. Waiting for Library scan.")
                    checkIndexed(tree.toString(), documentId, SystemClock.elapsedRealtime() + 30_000)
                }
            }
        }
    }

    private fun checkIndexed(root: String, documentId: String, deadline: Long) {
        if (isFinishing || isDestroyed) return
        if (LocalLibrary.indexing && SystemClock.elapsedRealtime() < deadline) {
            status.postDelayed({ checkIndexed(root, documentId, deadline) }, 250)
            return
        }
        worker.execute {
            val library = LocalLibrary.database(applicationContext)
            val id = library.folderDocuments(root).firstOrNull { it.second == documentId }?.first
            val song = id?.let(library::song)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) record(if (song?.available == true)
                    "Library indexed the verified test audio." else "Test audio saved. Library indexing was not confirmed.")
            }
        }
    }

    private fun importTestFile(tree: Uri, fixture: File): String {
        CaptureProbe.inspect(fixture, CancelScope())
        val resolver = contentResolver
        resolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "Evanna web probe ${UUID.randomUUID()}.flac"
        val document = checkNotNull(DocumentsContract.createDocument(resolver, parent, "audio/flac", name))
        try {
            fixture.inputStream().use { input ->
                checkNotNull(resolver.openOutputStream(document, "w")).use { output -> input.copyTo(output) }
            }
            val expected = fixture.readBytes()
            check(DataInputStream(checkNotNull(resolver.openInputStream(document))).use { input ->
                val actual = ByteArray(expected.size)
                input.readFully(actual)
                actual.contentEquals(expected) && input.read() == -1
            })
            return DocumentsContract.getDocumentId(document)
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw failure
        }
    }

    override fun onDestroy() {
        capture?.cancel()
        worker.shutdown()
        retained?.delete()
        (browser.parent as? android.view.ViewGroup)?.removeView(browser)
        browser.destroy()
        finished.complete(journal.joinToString("\n").ifBlank { "No observations." })
        super.onDestroy()
    }

    private companion object {
        const val FOLDER_REQUEST = 1
    }
}
