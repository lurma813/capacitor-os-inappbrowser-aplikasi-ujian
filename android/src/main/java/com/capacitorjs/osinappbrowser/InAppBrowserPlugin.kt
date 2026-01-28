package com.capacitorjs.osinappbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.lifecycleScope
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABClosable
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABEngine
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABRouter
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.helpers.OSIABFlowHelper
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.*
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.routeradapters.OSIABWebViewRouterAdapter

@CapacitorPlugin(name = "InAppBrowser")
class InAppBrowserPlugin : Plugin(), ComponentCallbacks2 {

    private var engine: OSIABEngine? = null
    private var activeRouter: OSIABRouter<Boolean>? = null

    /** WebView sudah benar-benar tampil */
    private var webViewFullyOpened = false

    override fun load() {
        super.load()
        engine = OSIABEngine()

        // Daftarkan callback sistem
        context.registerComponentCallbacks(this)
    }

    // =================================================
    // ANDROID CALLBACK — UI BENAR-BENAR HILANG
    // =================================================

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            forceCloseWebViewAndClearClipboard()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // tidak dipakai
    }

    override fun onLowMemory() {
        // optional
    }

    // =================================================
    // FORCE CLOSE + CLEAR CLIPBOARD
    // =================================================

    private fun forceCloseWebViewAndClearClipboard() {
        if (!webViewFullyOpened) return

        (activeRouter as? OSIABClosable)?.close {
            activeRouter = null
            webViewFullyOpened = false

            clearClipboard()

            notifyListeners(
                OSIABEventType.BROWSER_FINISHED.value,
                JSObject()
                    .put("reason", "app_backgrounded")
                    .put("clipboardCleared", true)
            )
        }
    }

    private fun clearClipboard() {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("", "")
            )
        } catch (_: Exception) {
            // Sengaja diabaikan — clipboard bukan operasi kritikal
        }
    }

    // =================================================
    // WEBVIEW
    // =================================================

    @PluginMethod
    fun openInWebView(call: PluginCall) {
        handleBrowserCall(call, OSInAppBrowserTarget.WEB_VIEW) { url ->
            val options = call.getObject("options")
                ?: return@handleBrowserCall sendErrorResult(
                    call,
                    OSInAppBrowserError.InputArgumentsIssue(OSInAppBrowserTarget.WEB_VIEW)
                )

            val customHeaders: Map<String, String>? =
                call.getObject("customHeaders")?.let { js ->
                    val map = mutableMapOf<String, String>()
                    js.keys().forEach { key ->
                        js.opt(key)?.let { value ->
                            map[key] = value.toString()
                        }
                    }
                    map
                }

            close {
                val webViewOptions = buildWebViewOptions(options)
                val router = OSIABWebViewRouterAdapter(
                    context = context,
                    lifecycleScope = activity.lifecycleScope,
                    options = webViewOptions,
                    customHeaders = customHeaders,
                    flowHelper = OSIABFlowHelper(),
                    onBrowserPageLoaded = {
                        webViewFullyOpened = true
                        notifyListeners(OSIABEventType.BROWSER_PAGE_LOADED.value, null)
                    },
                    onBrowserFinished = {
                        activeRouter = null
                        webViewFullyOpened = false
                        notifyListeners(OSIABEventType.BROWSER_FINISHED.value, null)
                    },
                    onBrowserPageNavigationCompleted = {
                        notifyListeners(
                            OSIABEventType.BROWSER_PAGE_NAVIGATION_COMPLETED.value,
                            JSObject().put("url", it)
                        )
                    }
                )

                engine?.openWebView(router, url) { success ->
                    if (success) {
                        activeRouter = router
                        webViewFullyOpened = false
                    }
                    handleBrowserResult(call, success, url, OSInAppBrowserTarget.WEB_VIEW)
                }
            }
        }
    }

    // =================================================
    // CLOSE MANUAL
    // =================================================

    @PluginMethod
    fun close(call: PluginCall) {
        close { success ->
            if (success) call.resolve()
            else sendErrorResult(call, OSInAppBrowserError.CloseFailed)
        }
    }

    private fun close(callback: (Boolean) -> Unit) {
        (activeRouter as? OSIABClosable)?.close { success ->
            if (success) {
                activeRouter = null
                webViewFullyOpened = false
            }
            callback(success)
        } ?: callback(false)
    }

    // =================================================
    // HELPERS
    // =================================================

    private fun handleBrowserCall(
        call: PluginCall,
        target: OSInAppBrowserTarget,
        action: (String) -> Unit
    ) {
        val url = call.getString("url")
        if (url.isNullOrEmpty()) {
            sendErrorResult(call, OSInAppBrowserError.InputArgumentsIssue(target))
            return
        }
        if (!isSchemeValid(url)) {
            sendErrorResult(call, OSInAppBrowserError.InvalidURL)
            return
        }
        try {
            action(url)
        } catch (_: Exception) {
            sendErrorResult(call, OSInAppBrowserError.OpenFailed(url, target))
        }
    }

    private fun handleBrowserResult(
        call: PluginCall,
        success: Boolean,
        url: String,
        target: OSInAppBrowserTarget
    ) {
        if (success) call.resolve()
        else sendErrorResult(call, OSInAppBrowserError.OpenFailed(url, target))
    }

    private fun isSchemeValid(url: String): Boolean {
        return url.startsWith("http://", true) || url.startsWith("https://", true)
    }

    private fun sendErrorResult(call: PluginCall, error: OSInAppBrowserError) {
        call.reject(error.message, error.code)
    }

    private fun buildWebViewOptions(options: JSObject): OSIABWebViewOptions {
        val android = options.getJSObject("android")
        return OSIABWebViewOptions(
            showURL = options.getBoolean("showURL", true) ?: true,
            showToolbar = options.getBoolean("showToolbar", true) ?: true,
            clearCache = options.getBoolean("clearCache", true) ?: true,
            clearSessionCache = options.getBoolean("clearSessionCache", true) ?: true,
            mediaPlaybackRequiresUserAction = options.getBoolean("mediaPlaybackRequiresUserAction", false) ?: false,
            closeButtonText = options.getString("closeButtonText") ?: "Close",
            toolbarPosition = options.getInteger("toolbarPosition")
                ?.let { OSIABToolbarPosition.entries[it] }
                ?: OSIABToolbarPosition.TOP,
            leftToRight = options.getBoolean("leftToRight", false) ?: false,
            showNavigationButtons = options.getBoolean("showNavigationButtons", false) ?: false,
            allowZoom = android?.getBoolean("allowZoom", true) ?: true,
            hardwareBack = android?.getBoolean("hardwareBack", true) ?: true,
            pauseMedia = android?.getBoolean("pauseMedia", true) ?: true,
            customUserAgent = options.getString("customWebViewUserAgent", null)
        )
    }
}

// =================================================
// EVENTS
// =================================================

enum class OSIABEventType(val value: String) {
    BROWSER_FINISHED("browserClosed"),
    BROWSER_PAGE_LOADED("browserPageLoaded"),
    BROWSER_PAGE_NAVIGATION_COMPLETED("browserPageNavigationCompleted")
}
