package co.relishfoods.approvals

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var initialPageLoaded = false

    // Registers the UPI Intent launcher and handles the result
    private val upiLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleUpiResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // Supabase auth requires this
            allowFileAccess = true            // Bill attachment uploads
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // Handle URL interception
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                return when {
                    // Intercept UPI deep links — launch natively for Intent result
                    url.startsWith("upi://") -> {
                        launchUpiIntent(url)
                        true
                    }
                    // GPay intent:// links — launch natively
                    url.startsWith("intent://") -> {
                        launchIntentUrl(url)
                        true
                    }
                    // Keep all other URLs inside the WebView
                    else -> false
                }
            }

            // Handle share intent after the initial page load is complete.
            // Only fires once — subsequent navigations are ignored.
            override fun onPageFinished(view: WebView, url: String) {
                if (!initialPageLoaded) {
                    initialPageLoaded = true
                    handleShareIntent(intent)
                }
            }
        }

        // Enable JavaScript alerts, console messages etc.
        webView.webChromeClient = WebChromeClient()

        // Enable WebView debugging in debug builds (inspect via chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        // Load the app
        webView.loadUrl("https://relishvoucher.vercel.app")
    }

    // Launches a standard UPI deep link as an Android Intent
    // Uses startActivityForResult so we receive the payment outcome
    private fun launchUpiIntent(upiUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(upiUrl)
        }
        try {
            upiLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // No UPI app installed on this device
            notifyWebView(
                status = "FAILURE",
                error = "NO_UPI_APP",
                txnId = "",
                txnRef = "",
                responseCode = ""
            )
            Toast.makeText(this, "No UPI app found", Toast.LENGTH_SHORT).show()
        }
    }

    // Parses Android intent:// scheme URLs (used by GPay direct deep link)
    private fun launchIntentUrl(intentUrl: String) {
        try {
            val intent = Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME)
            upiLauncher.launch(intent)
        } catch (e: Exception) {
            notifyWebView(
                status = "FAILURE",
                error = "INTENT_PARSE_ERROR",
                txnId = "",
                txnRef = "",
                responseCode = ""
            )
        }
    }

    // Processes the result returned by GPay / UPI app after payment
    private fun handleUpiResult(result: ActivityResult) {
        val data = result.data

        // UPI apps return result in Intent extras
        // Key names vary slightly between apps — check both cases
        val status = data?.getStringExtra("Status")
            ?: data?.getStringExtra("status")
            ?: "UNKNOWN"

        val txnId = data?.getStringExtra("txnId") ?: ""
        val txnRef = data?.getStringExtra("txnRef") ?: ""
        val responseCode = data?.getStringExtra("responseCode") ?: ""

        // SUCCESS = Status is SUCCESS (case-insensitive) AND responseCode is 00
        // responseCode 00 = approved by bank
        val isSuccess = status.equals("SUCCESS", ignoreCase = true)
                && responseCode == "00"

        notifyWebView(
            status = if (isSuccess) "SUCCESS" else "FAILURE",
            error = if (!isSuccess && status == "UNKNOWN") "NO_RESPONSE" else "",
            txnId = txnId,
            txnRef = txnRef,
            responseCode = responseCode
        )
    }

    // Calls window.onUpiResult() in the WebView JavaScript context
    // This is how the native layer communicates the payment result
    // back to the PWA running inside the WebView
    private fun notifyWebView(
        status: String,
        error: String,
        txnId: String,
        txnRef: String,
        responseCode: String
    ) {
        // Build a safe JSON string — escape quotes in values
        fun esc(s: String) = s.replace("\"", "\\\"")

        val json = """
            {
              "status": "${esc(status)}",
              "error": "${esc(error)}",
              "txnId": "${esc(txnId)}",
              "txnRef": "${esc(txnRef)}",
              "responseCode": "${esc(responseCode)}"
            }
        """.trimIndent()

        // evaluateJavascript must run on the main thread
        webView.post {
            webView.evaluateJavascript(
                "window.onUpiResult && window.onUpiResult($json)",
                null
            )
        }
    }

    // Handles shares when the app is already running (foreground or background).
    // Android calls onNewIntent instead of onCreate for a singleTask activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // Reads a shared file (image or PDF) from a bank/UPI app, base64-encodes it,
    // and injects it into the WebView via window.onReceiptShared().
    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val mimeType = intent.type ?: return
        if (!mimeType.startsWith("image/") && mimeType != "application/pdf") return

        val uri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return

        // Read on a background thread — never on main thread
        Thread {
            try {
                // Resolve display name (bank apps use content:// URIs)
                var fileName = "receipt"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) fileName = cursor.getString(col) ?: "receipt"
                }

                val inputStream = contentResolver.openInputStream(uri) ?: return@Thread
                val bytes = inputStream.readBytes()
                inputStream.close()

                // Base64 chars are [A-Za-z0-9+/=] — safe to embed in a JS string.
                // Only the fileName needs sanitisation.
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val safeMime = mimeType.replace("'", "")
                val safeName = fileName.replace("\\", "").replace("'", "").replace("\"", "")

                // Build the JS call
                val js = """
                    (function() {
                        if (typeof window.onReceiptShared === 'function') {
                            // App is ready — dispatch immediately
                            window.onReceiptShared({
                                mimeType: '$safeMime',
                                base64Data: '$base64',
                                fileName: '$safeName'
                            });
                        } else {
                            // App still loading (cold start) — stash for when it registers
                            window._pendingSharedReceipt = {
                                mimeType: '$safeMime',
                                base64Data: '$base64',
                                fileName: '$safeName'
                            };
                        }
                    })();
                """.trimIndent()

                runOnUiThread { webView.evaluateJavascript(js, null) }
            } catch (e: Exception) {
                android.util.Log.e("RelishApprovals", "Share intent handling failed: ${e.message}")
            }
        }.start()
    }

    // Handle Android back button — go back in WebView history
    // before exiting the app
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
