package id.my.vallsite.kasir

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var printer: BluetoothPrinter
    private lateinit var ble: WebBluetoothBridge

    private val startUri: Uri = Uri.parse(BuildConfig.START_URL)
    private var failedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPress = 0L

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingBle: Pair<() -> Unit, () -> Unit>? = null

    private val blePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val pending = pendingBle
            pendingBle = null
            if (pending != null) {
                if (result.values.all { it }) pending.first() else pending.second()
            }
        }

    /**
     * Memastikan izin untuk memindai dan menyambung Bluetooth sudah ada (Android 12+: Perangkat
     * di sekitar; Android 11 ke bawah: Lokasi), lalu menjalankan [onGranted] atau [onDenied].
     */
    fun withBlePermissions(onGranted: () -> Unit, onDenied: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            pendingBle?.second?.invoke()
            pendingBle = Pair(onGranted, onDenied)
            blePermissions.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Layar tetap menyala selama kasir dibuka. Hapus baris ini kalau tidak mau.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        printer = BluetoothPrinter(this)
        ble = WebBluetoothBridge(this, webView, printer)

        requestBluetoothPermissionIfNeeded()
        setupWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        webView.canGoBack() -> webView.goBack()
                        System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                        else -> {
                            lastBackPress = System.currentTimeMillis()
                            Toast.makeText(
                                this@MainActivity,
                                R.string.tekan_lagi_keluar,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(BuildConfig.START_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.brand))

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // login & data lokal situs tetap tersimpan
            allowFileAccess = false // halaman error di assets tetap bisa dibuka
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
            // Situs bisa mendeteksi aplikasi lewat "KasirApp" di user agent.
            userAgentString = "$userAgentString KasirApp/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(printer, "AndroidPrinter")
        webView.addJavascriptInterface(AppBridge(), "App")
        webView.addJavascriptInterface(ble, "AndroidBle")
        installBluetoothShim()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                view.evaluateJavascript(shimJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                view.evaluateJavascript(shimJs, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    failedUrl = request.url.toString()
                    view.loadUrl("file:///android_asset/error.html")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    filePicker.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    /** Skrip yang membuat navigator.bluetooth tersedia di dalam WebView (lihat assets/bluetooth-shim.js). */
    private val shimJs: String by lazy {
        assets.open("bluetooth-shim.js").bufferedReader().use { it.readText() }
    }

    /**
     * Memasang shim sebelum skrip halaman berjalan. Kalau WebView tidak mendukungnya, shim tetap
     * disuntikkan saat halaman mulai dan selesai dimuat (aman dijalankan berulang).
     */
    private fun installBluetoothShim() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origin = "${startUri.scheme}://${startUri.authority}"
            WebViewCompat.addDocumentStartJavaScript(webView, shimJs, setOf(origin))
        }
    }

    /** Tautan di domain kasir dibuka di dalam aplikasi; selebihnya (WhatsApp, telepon, dll.) keluar. */
    private fun isInternal(uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.startsWith("/android_asset/") == true
        val scheme = uri.scheme
        return (scheme == "https" || scheme == "http") &&
            uri.host.equals(startUri.host, ignoreCase = true)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.tidak_bisa_buka, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Dipakai halaman error (assets/error.html). */
    private inner class AppBridge {
        @JavascriptInterface
        fun retry() {
            runOnUiThread { webView.loadUrl(failedUrl ?: BuildConfig.START_URL) }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        ble.shutdown()
        printer.disconnect()
        webView.destroy()
        super.onDestroy()
    }
}
