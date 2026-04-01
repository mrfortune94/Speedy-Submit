package com.javanxd.speedysubmit

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var goButton: Button
    private lateinit var refreshButton: Button
    private var currentLongPressUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlEditText = findViewById(R.id.urlEditText)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        goButton = findViewById(R.id.goButton)
        refreshButton = findViewById(R.id.refreshButton)

        setupWebView()
        setupButtons()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        WebView.setWebContentsDebuggingEnabled(true) // Enable Chrome DevTools

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                urlEditText.setText(url)
                // injectLongPressScript()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // For race conditions, but handle in JS
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = WebChromeClient()

        // webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.loadUrl("https://example.com")
    }

    private fun setupButtons() {
        backButton.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        forwardButton.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        goButton.setOnClickListener {
            val url = urlEditText.text.toString()
            webView.loadUrl(if (url.startsWith("http")) url else "https://$url")
        }
        refreshButton.setOnClickListener {
            webView.reload()
        }
    }

    private fun injectLongPressScript() {
        val script = """
            (function() {
                var longPressTimer;
                var longPressElement;
                var longPressUrl;

                function getElementUrl(element) {
                    if (element.tagName === 'A') return element.href;
                    if (element.tagName === 'IMG') return element.src;
                    if (element.tagName === 'FORM') return element.action || window.location.href;
                    return window.location.href;
                }

                document.addEventListener('touchstart', function(e) {
                    longPressElement = e.target;
                    longPressUrl = getElementUrl(longPressElement);
                    longPressTimer = setTimeout(function() {
                        Android.onLongPress(longPressUrl, longPressElement.outerHTML);
                    }, 3000);
                });

                document.addEventListener('touchend', function(e) {
                    clearTimeout(longPressTimer);
                });

                document.addEventListener('touchmove', function(e) {
                    clearTimeout(longPressTimer);
                });
            })();
        """
        webView.evaluateJavascript(script, null)
    }

    // inner class WebAppInterface {
    //     @JavascriptInterface
    //     fun onLongPress(url: String, html: String) {
    //         currentLongPressUrl = url
    //         runOnUiThread {
    //             showLongPressDialog(url, html)
    //         }
    //     }
    // }

    private fun showLongPressDialog(url: String, html: String) {
        val options = arrayOf("View Source", "Test Race Conditions", "Copy URL", "Open in External Browser", "Inspect Element")
        AlertDialog.Builder(this)
            .setTitle("Options for $url")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewSource(url)
                    1 -> testRaceConditions(url)
                    2 -> copyUrl(url)
                    3 -> openInExternalBrowser(url)
                    4 -> inspectElement(html)
                }
            }
            .show()
    }

    private fun viewSource(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val source = response.body?.string() ?: "No content"
                withContext(Dispatchers.Main) {
                    showSourceDialog(source)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to fetch source: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSourceDialog(source: String) {
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.text = source
        textView.setPadding(16, 16, 16, 16)
        scrollView.addView(textView)
        AlertDialog.Builder(this)
            .setTitle("Page Source")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun testRaceConditions(url: String) {
        val numRequests = 100 // Adjust as needed
        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient()
            val jobs = List(numRequests) {
                async {
                    try {
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        "Request ${it + 1}: ${response.code}"
                    } catch (e: IOException) {
                        "Request ${it + 1}: Failed"
                    }
                }
            }
            val results = jobs.awaitAll()
            withContext(Dispatchers.Main) {
                val resultText = results.joinToString("\n")
                showResultsDialog("Race Conditions Results", resultText)
            }
        }
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun openInExternalBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun inspectElement(html: String) {
        AlertDialog.Builder(this)
            .setTitle("Element HTML")
            .setMessage(html)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showResultsDialog(title: String, message: String) {
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.text = message
        textView.setPadding(16, 16, 16, 16)
        scrollView.addView(textView)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }
}