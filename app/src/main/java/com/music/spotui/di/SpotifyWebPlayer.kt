package com.music.spotui.di

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

object SpotifyWebPlayer {
    private const val TAG = "SpotifyWebPlayer"
    private const val BASE_URL = "https://open.spotify.com"
    private const val PREVIEW_MAX_MS = 35_000L
    private const val END_TAIL_MS = 1_000L
    private const val END_WRAP_WINDOW_MS = 2_500L
    private const val DEFAULT_BLOCK_MINUTES = 30L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile var pageReady = false
        private set
    @Volatile var canPlay = false
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var isPlaying = false
        private set
    @Volatile var hasStarted = false
        private set
    @Volatile private var blockedUntilMs = 0L
    @Volatile private var currentUri: String? = null
    @Volatile private var endedUri: String? = null
    @Volatile private var lastPositionMs = 0L

    @Volatile var onStateChanged: (() -> Unit)? = null
    @Volatile var onTrackEnded: (() -> Unit)? = null

    private val bridge = object {
        @JavascriptInterface
        fun onWidevine(ok: Boolean) {
            canPlay = ok
            Log.d(TAG, "widevine canPlay=$ok")
        }

        @JavascriptInterface
        fun onReady() {
            pageReady = true
        }

        @JavascriptInterface
        fun onUpdate(position: Double, duration: Double, paused: Boolean, buffering: Boolean, uri: String) {
            handleUpdate(position.toLong(), duration.toLong(), paused, uri)
        }
    }

    private fun handleUpdate(position: Long, duration: Long, paused: Boolean, uri: String) {
        val expected = currentUri ?: return
        if (uri.isNotEmpty() && uri != expected) return
        positionMs = position
        if (duration > 0) durationMs = duration
        isPlaying = !paused
        if (!paused && duration > 0 && position > 0) hasStarted = true
        val nearEnd = duration > 0 && (
            position >= duration - END_TAIL_MS ||
                (lastPositionMs >= duration - END_WRAP_WINDOW_MS && position < END_TAIL_MS)
            )
        lastPositionMs = position
        if (paused && hasStarted && nearEnd && endedUri != expected) {
            endedUri = expected
            isPlaying = false
            mainHandler.post { onTrackEnded?.invoke() }
        }
        mainHandler.post { onStateChanged?.invoke() }
    }

    fun notifyStateChanged() {
        mainHandler.post { onStateChanged?.invoke() }
    }

    fun isAvailable(context: Context): Boolean =
        webView != null &&
            canPlay &&
            System.currentTimeMillis() >= blockedUntilMs &&
            com.music.spotui.data.api.SpotifySession.spDc(context).isNotBlank()

    fun markUnavailable(minutes: Long = DEFAULT_BLOCK_MINUTES) {
        blockedUntilMs = System.currentTimeMillis() + minutes * 60_000L
    }

    fun isPreviewOnly(expectedDurationMs: Long): Boolean =
        durationMs in 1..PREVIEW_MAX_MS && expectedDurationMs > 60_000L

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Activity) {
        if (webView != null) return
        if (com.music.spotui.data.api.SpotifySession.spDc(activity).isBlank()) {
            Log.d(TAG, "attach deferred: user not logged in yet")
            return
        }
        try {
            val wv = WebView(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            wv.addJavascriptInterface(bridge, "SpotuiBridge")
            seedCookies(activity, wv)
            val appContext = activity.applicationContext
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    if (request == null) return null
                    return interceptForAds(appContext, request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(WIDEVINE_PROBE_JS, null)
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val resources = request?.resources ?: return
                    val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    if (resources.contains(drm)) {
                        request.grant(arrayOf(drm))
                    } else {
                        request.deny()
                    }
                }

                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "console: ${m.message()} @${m.lineNumber()}")
                    return true
                }
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            activity.addContentView(wv, params)
            wv.post { wv.translationX = wv.width.toFloat() + 2000f }
            loadPage(wv)
            webView = wv
            Log.d(TAG, "WebView attached")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    private fun seedCookies(context: Context, wv: WebView) {
        val spDc = com.music.spotui.data.api.SpotifySession.spDc(context)
        if (spDc.isBlank()) return
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
            val attrs = "Domain=.spotify.com; Path=/; Secure"
            setCookie(BASE_URL, "sp_dc=$spDc; $attrs")
            setCookie("https://spotify.com", "sp_dc=$spDc; $attrs")
            flush()
        }
    }

    private fun loadPage(wv: WebView) {
        pageReady = false
        wv.loadDataWithBaseURL(BASE_URL, PAGE_HTML, "text/html", "UTF-8", null)
    }

    fun refreshLogin(context: Context) {
        if (com.music.spotui.data.api.SpotifySession.spDc(context).isBlank()) return
        blockedUntilMs = 0L
        val wv = webView
        if (wv == null) {
            if (context is Activity) attach(context)
            return
        }
        seedCookies(context, wv)
        wv.post { loadPage(wv) }
    }

    fun play(trackId: String, startMs: Long = 0L) = playUri("spotify:track:$trackId", startMs)

    fun playEpisode(episodeId: String, startMs: Long = 0L) = playUri("spotify:episode:$episodeId", startMs)

    private fun playUri(uri: String, startMs: Long) {
        val wv = webView ?: run {
            Log.w(TAG, "play before attach")
            return
        }
        currentUri = uri
        endedUri = null
        hasStarted = false
        positionMs = 0L
        durationMs = 0L
        lastPositionMs = 0L
        isPlaying = false
        wv.post { wv.evaluateJavascript("window.spotuiPlay('$uri', ${startMs / 1000});", null) }
    }

    fun resume() = eval("window.spotuiCall('resume');")

    fun pause() {
        eval("window.spotuiCall('pause');")
        isPlaying = false
    }

    fun stop() {
        pause()
        currentUri = null
        hasStarted = false
        positionMs = 0L
        durationMs = 0L
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        eval("window.spotuiSeek(${target / 1000});")
        this.positionMs = if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    fun release() {
        webView?.let { wv ->
            wv.post {
                runCatching {
                    wv.stopLoading()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
        }
        webView = null
        pageReady = false
        currentUri = null
    }

    private fun eval(js: String) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js, null) }
    }

    // ── Ad blocking (SpotiFuck strategy) ────────────────────────────────
    // Analytics/telemetry hosts → answered with an empty 200 so nothing tracks.
    private val analyticsHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "fastly-insights.com",
        "sentry.io",
    )
    // Audio-ad sources → answered with a bundled silent.mp3. Only UNAMBIGUOUS ad
    // hosts here: the shared scdn.co/audio/ + spotifycdn.com/audio/ were REMOVED
    // because real track audio streams from them too — probing every segment there
    // (to check content-type) throttled playback (stutter/restart/lag). These
    // remaining hosts are ad-only, so a cheap URL match is safe.
    private val audioAdPatterns = listOf(
        "scdn.co/mp3-ad/",
        "amillionads.com",
        "2mdn.net",
        "adxcel.com",
        "adstudio-assets.scdn.co",
    )

    private fun interceptForAds(
        context: Context,
        request: android.webkit.WebResourceRequest,
    ): android.webkit.WebResourceResponse? {
        val url = request.url.toString()
        val lower = url.lowercase()

        // Analytics/telemetry: answer with an empty 200 — no network needed.
        if (analyticsHosts.any { it in lower }) {
            return android.webkit.WebResourceResponse(
                "text/plain", "utf-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(ByteArray(0)),
            )
        }

        // Never touch streaming media segments (they carry a Range header) — those
        // are the real audio/video being fetched in chunks; intercepting them is
        // what caused the stutter. Ad-only hosts get a silent.mp3.
        val isRange = request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }
        if (!isRange && audioAdPatterns.any { it in lower }) {
            Log.d(TAG, "adblock: silenced audio ad $url")
            return runCatching {
                android.webkit.WebResourceResponse(
                    "audio/mpeg", null, context.assets.open("silent.mp3"),
                )
            }.getOrNull()
        }
        return null
    }

    private const val WIDEVINE_PROBE_JS = """
        (function(){
          navigator.requestMediaKeySystemAccess('com.widevine.alpha',
            [{initDataTypes:['cenc'],audioCapabilities:[{contentType:'audio/mp4;codecs="mp4a.40.2"'}]}])
            .then(function(){ SpotuiBridge.onWidevine(true); })
            .catch(function(){ SpotuiBridge.onWidevine(false); });
        })();
    """

    private const val PAGE_HTML = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000}</style>
        </head>
        <body>
        <div id="host"></div>
        <script src="https://open.spotify.com/embed/iframe-api/v1" async></script>
        <script>
        var api = null, ctrl = null, creating = false, pending = null;
        function wire(c) {
          c.addListener('ready', function() { SpotuiBridge.onReady(); c.play(); });
          c.addListener('playback_update', function(e) {
            var d = e.data || {};
            SpotuiBridge.onUpdate(d.position || 0, d.duration || 0, !!d.isPaused, !!d.isBuffering, d.playingURI || '');
          });
        }
        function create(uri) {
          creating = true;
          api.createController(document.getElementById('host'), { uri: uri, width: '100%', height: '152' }, function(c) {
            ctrl = c;
            creating = false;
            wire(c);
            if (pending) { var p = pending; pending = null; c.loadUri(p.uri, false, p.start); c.play(); }
          });
        }
        window.onSpotifyIframeApiReady = function(a) {
          api = a;
          if (pending) { var p = pending; pending = null; create(p.uri); }
        };
        window.spotuiPlay = function(uri, startSec) {
          if (ctrl) { ctrl.loadUri(uri, false, startSec || 0); ctrl.play(); return 'load'; }
          if (api && !creating) { create(uri); return 'create'; }
          pending = { uri: uri, start: startSec || 0 };
          return 'wait';
        };
        window.spotuiCall = function(name) { if (ctrl && ctrl[name]) { ctrl[name](); } };
        window.spotuiSeek = function(sec) { if (ctrl) { ctrl.seek(sec); } };
        </script>
        </body>
        </html>
    """
}
