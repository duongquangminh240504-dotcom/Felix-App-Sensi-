package com.felix.sensi

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import rikka.shizuku.Shizuku

class AndroidInterface(private val webView: WebView) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            notifyJs(granted, if (granted) "Shizuku permission granted by user" else "User denied Shizuku permission")
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        notifyLog("Shizuku service connected", "info")
    }

    init {
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener {
            notifyLog("Shizuku service stopped. Start Shizuku again.", "warn")
        }
    }

    /** Chạy lệnh shell dưới quyền Shizuku (uid 2000). Gọi từ background thread của WebView. */
    @JavascriptInterface
    fun runShellCommand(cmd: String): String {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()

            if (exit == 0) stdout.ifBlank { "OK" }
            else throw RuntimeException("exit=$exit ${stderr.trim()}")
        } catch (e: Exception) {
            throw RuntimeException(e.message ?: "Shell error")
        }
    }

    @JavascriptInterface
    fun requestShizukuPermission() {
        mainHandler.post {
            if (!Shizuku.pingBinder()) {
                notifyJs(false, "Shizuku is not running. Start it via Wireless Debugging or ADB first.")
                return@post
            }

            if (Shizuku.isPreV11()) {
                notifyJs(false, "Shizuku version too old. Please update Shizuku.")
                return@post
            }

            val status = try {
                Shizuku.checkSelfPermission()
            } catch (e: Exception) {
                notifyLog("checkSelfPermission failed: ${e.message}", "error")
                PackageManager.PERMISSION_DENIED
            }
            if (status == PackageManager.PERMISSION_GRANTED) {
                notifyJs(true, "Shizuku already granted")
                return@post
            }

            if (Shizuku.shouldShowRequestPermissionRationale()) {
                notifyLog("Previously denied. Requesting again.", "warn")
            }
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.requestPermission(REQUEST_CODE)
            notifyLog("Permission request sent to Shizuku. Check the popup.", "info")
        }
    }

    private fun notifyJs(granted: Boolean, message: String) {
        val safe = message.replace("\\", "\\\\").replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript("window.onShizukuResult($granted, '$safe')", null)
        }
    }

    private fun notifyLog(message: String, type: String) {
        val safe = message.replace("\\", "\\\\").replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript("window.onNativeLog('$safe', '$type')", null)
        }
    }

    companion object {
        const val REQUEST_CODE = 1001
    }
}
