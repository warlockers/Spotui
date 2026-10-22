package com.music.spotui.di

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Experimental "real Spotify" playback: rather than resolve a track from
 * YouTube/FLAC, we host Spotify's OWN web player in a hidden 1×1 WebView and let
 * it stream + play the track itself (its DRM/decoding, not ours — no bypass).
 * Our native UI becomes a remote control that drives the web player's on-page
 * buttons (data-testid controls) and reads its now-playing state.
 *
 * Requirements / limits:
 *  - The WebView reuses the login cookie jar (sp_dc etc.), so the user must have
 *    logged in via SpotifyLoginScreen first.
 *  - Free tier works (desktop web allows on-demand) but injects ads; Premium is
 *    ad-free. Playback is real-time only: no seek-ahead / download / crossfade.
 *  - Whether audio actually plays depends on Widevine being provisioned in the
 *    system WebView; if it isn't, the page loads but stays silent.
 */
object SpotifyWebPlayer {
    private const val TAG = "SpotifyWebPlayer"
    private const val FALLBACK_CHROME_VERSION = "131.0.0.0"
    private const val NAVIGATE_PLAY_WINDOW_MS = 40_000L
    private const val COMMAND_RESEND_MS = 8_000L
    private const val CLICK_FALLBACK_MS = 20_000L
    private const val MAX_DIALOG_TRIES = 6
    private val ENGLISH_HEADERS = mapOf("Accept-Language" to "en-US,en;q=0.9")
    private const val SPOTIFY_ORIGIN = "https://open.spotify.com"
    private const val MAX_AD_IDS = 32
    @Volatile private var activityRef: java.lang.ref.WeakReference<Activity>? = null
    private val adIds = LinkedHashSet<String>()
    @Volatile private var lastRecreateMs = 0L
    @Volatile var lastClickResult: String? = null
    @Volatile var lastCommandResult: String? = null
    @Volatile private var tapOurs = false
    @Volatile private var tapPlaying = false
    @Volatile private var tapDuration = 0L
    @Volatile private var tapPosBase = 0L
    @Volatile private var tapAtMs = 0L
    @Volatile private var tapUri = ""
    @Volatile private var tapMsgs = 0
    @Volatile private var tapActive = ""
    @Volatile private var tapLinkedUri = ""

    fun trackMatches(uri: String): Boolean? {
        if (tapAtMs == 0L || !tapOurs || tapUri.isEmpty()) return null
        return tapUri == uri || tapLinkedUri == uri
    }

    fun tapSummary(): String =
        if (tapMsgs == 0) "none" else "msgs=$tapMsgs ours=$tapOurs active=${tapActive.take(6)} playing=$tapPlaying uri=$tapUri age=${if (tapAtMs == 0L) -1 else (System.currentTimeMillis() - tapAtMs) / 1000}s"

    @Volatile private var webView: WebView? = null
    @Volatile var pageReady = false
        private set

    // Android's System WebView ships WITHOUT the Widevine CDM (only full Chrome
    // has it), so Spotify's DRM web player can't decrypt audio here — it shows
    // "playing" but stays silent. We probe for Widevine on load; when it's absent
    // (all current Android WebViews) callers must NOT route playback here.
    @Volatile var canPlay = false
        private set

    // Live playback position/duration (ms) scraped from the web player's own
    // progress bar, so the app's UI (which reads these) shows real time instead
    // of the idle ExoPlayer's 0:00. Updated by a 500ms poll while attached.
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var isPlaying = false
        private set

    // True once the web player has registered a device and we've captured its
    // auth/client tokens — then we switch tracks via Spotify's own command API
    // (playFromUri) instead of reloading the page for each one.
    @Volatile private var commandReady = false

    // True once the first play navigated+clicked, activating the web player's
    // Connect device — a prerequisite for the command API to actually start audio.
    @Volatile private var activated = false
    val isWarm: Boolean get() = activated && commandReady

    /** Invoked (on the main thread) after each progress poll so the media
     *  notification's SimpleBasePlayer can refresh its state. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun applyPlayerTap() {
        val at = tapAtMs
        if (at == 0L || !tapOurs) return
        isPlaying = tapPlaying
        if (tapDuration > 0) durationMs = tapDuration
        positionMs = if (tapPlaying) tapPosBase + (System.currentTimeMillis() - at) else tapPosBase
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val wv = webView
            if (wv != null) {
                wv.evaluateJavascript(progressJs()) { r ->
                    // r is "pos|dur|playing" in seconds (or -1 fields when unknown).
                    val s = r?.trim('"') ?: ""
                    val parts = s.split("|")
                    if (parts.size == 3) {
                        parts[0].toDoubleOrNull()?.let { if (it >= 0) positionMs = (it * 1000).toLong() }
                        parts[1].toDoubleOrNull()?.let { if (it >= 0) durationMs = (it * 1000).toLong() }
                        isPlaying = parts[2] == "1"
                        applyPlayerTap()
                        onStateChanged?.invoke()
                    } else {
                        applyPlayerTap()
                        onStateChanged?.invoke()
                    }
                }
            }
            pollHandler.postDelayed(this, 500)
        }
    }

    /** Attach the hidden WebView to the activity window (media won't play detached). */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Activity) {
        if (webView != null) return
        val spDc = com.music.spotui.data.api.SpotifySession.spDc(activity)
        if (spDc.isBlank()) {
            Log.d(TAG, "attach deferred: user not logged in yet")
            return
        }
        try {
            // Lets `chrome://inspect` attach to the hidden player for diagnosis.
            WebView.setWebContentsDebuggingEnabled(true)
            val wv = WebView(activity)
            activityRef = java.lang.ref.WeakReference(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = desktopUserAgent(chromeVersion(activity))
                @Suppress("DEPRECATION")
                setSupportMultipleWindows(false)
            }
            // Bridge so the page can report the async Widevine probe result back —
            // evaluateJavascript can't await a Promise, so we call in via JS instead.
            wv.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onWidevine(ok: Boolean) {
                    canPlay = ok
                    Log.d(TAG, "widevine canPlay=$ok (via bridge)")
                }
                @android.webkit.JavascriptInterface
                fun onAdIds(json: String) {
                    recordAdIds(json)
                }
                @android.webkit.JavascriptInterface
                fun onPlayerState(
                    active: String, devId: String, playing: Boolean,
                    position: Double, duration: Double, stateTs: Double, serverTs: Double,
                    uri: String, linkedUri: String,
                ) {
                    tapMsgs++
                    tapOurs = devId.isNotEmpty() && active == devId
                    tapActive = active
                    if (!tapOurs) return
                    tapUri = uri
                    tapLinkedUri = linkedUri
                    tapPlaying = playing
                    tapDuration = duration.toLong()
                    tapPosBase = position.toLong() + (if (serverTs > 0 && stateTs > 0) (serverTs - stateTs).toLong().coerceAtLeast(0L) else 0L)
                    tapAtMs = System.currentTimeMillis()
                }
                @android.webkit.JavascriptInterface
                fun onCommandResult(status: Int, body: String) {
                    val entry = "$status ${body.take(60)}"
                    lastCommandResult = listOfNotNull(lastCommandResult?.substringAfterLast(" ; "), entry).joinToString(" ; ")
                    Log.d(TAG, "command result: $lastCommandResult")
                }
                @android.webkit.JavascriptInterface
                fun onProbe(json: String) {
                    probeCallback?.invoke(json)
                }
                @android.webkit.JavascriptInterface
                fun onCommandReady() {
                    // The web player registered a device + we captured its tokens, so
                    // we can now switch tracks via the connect-state command API
                    // (no page reload → no "Oops, something went wrong").
                    if (!commandReady) { commandReady = true; Log.d(TAG, "command API ready") }
                }
            }, "SpotuiBridge")

            val cookies = CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
            // Seed the auth cookie for the Spotify domain before we load the page.
            val attrs = "Domain=.spotify.com; Path=/; Secure"
            cookies.setCookie("https://open.spotify.com", "sp_dc=$spDc; $attrs")
            cookies.setCookie("https://spotify.com", "sp_dc=$spDc; $attrs")
            cookies.flush()
            Log.d(TAG, "seeded sp_dc cookie into WebView jar")
            val appContext = activity.applicationContext
            applyBrowserIdentity(wv, chromeVersion(activity))
            val docStartInstalled = installDocumentStartScripts(wv)
            wv.webViewClient = object : WebViewClient() {
                // Ad-free ("unlocked") playback, SpotiFuck-style: drop analytics and
                // swap audio-ad streams for silence at the network layer, so the free
                // web player never plays or counts an ad.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    if (request == null) return null
                    return interceptForAds(appContext, request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    synchronized(adIds) { adIds.clear() }
                    tapAtMs = 0L
                    tapOurs = false
                    // Inject as EARLY as possible: the off-screen WebView reports the
                    // page as hidden, and Spotify's Web Playback SDK refuses to
                    // register a device / start audio on a hidden page. Spoof the Page
                    // Visibility API to always report visible+focused.
                    if (!docStartInstalled) {
                        view?.evaluateJavascript(VISIBILITY_SPOOF_JS, null)
                        view?.evaluateJavascript(LOCALE_SPOOF_JS, null)
                        view?.evaluateJavascript(MEDIA_TRACK_JS, null)
                        view?.evaluateJavascript(AD_STATE_HOOK_JS, null)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    val owner = activityRef?.get()
                    release()
                    commandReady = false
                    activated = false
                    val now = System.currentTimeMillis()
                    if (owner != null && !owner.isFinishing && now - lastRecreateMs > 10_000L) {
                        lastRecreateMs = now
                        owner.runOnUiThread { attach(owner) }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = url?.contains("open.spotify.com") == true
                    Log.d(TAG, "onPageFinished ready=$pageReady url=$url")
                    view?.evaluateJavascript(VISIBILITY_SPOOF_JS, null)
                    // Command-API bootstrap: capture the player's device id + tokens
                    // so we can transfer playback to THIS device and switch tracks
                    // without reloading.
                    view?.evaluateJavascript(BOOTSTRAP_JS, null)
                    // Report whether the page thinks it's logged in (auth cookie present).
                    view?.evaluateJavascript(
                        "(function(){return document.cookie.indexOf('sp_dc')>-1 ? 'has-sp_dc' : 'no-sp_dc';})();"
                    ) { Log.d(TAG, "login-check: $it") }
                    // Probe Widevine and report the async result via the bridge.
                    // Works because onPermissionRequest (below) auto-grants the
                    // PROTECTED_MEDIA_ID permission the CDM needs.
                    view?.evaluateJavascript(
                        """
                        (function(){
                          navigator.requestMediaKeySystemAccess('com.widevine.alpha',
                            [{initDataTypes:['cenc'],audioCapabilities:[{contentType:'audio/mp4;codecs="mp4a.40.2"'}]}])
                            .then(function(){ SpotuiBridge.onWidevine(true); })
                            .catch(function(){ SpotuiBridge.onWidevine(false); });
                        })();
                        """.trimIndent(),
                        null,
                    )
                }
            }
            wv.webChromeClient = object : android.webkit.WebChromeClient() {
                // THE fix: Spotify's player needs the PROTECTED_MEDIA_ID (Widevine)
                // permission to decrypt audio. WebView asks the app to grant it; if
                // we don't, EME fails and playback is silent. Grant DRM, deny the rest.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val drm = android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    if (request.resources.contains(drm)) {
                        Log.d(TAG, "granting PROTECTED_MEDIA_ID (Widevine)")
                        request.grant(arrayOf(drm))
                    } else {
                        request.deny()
                    }
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
            // Give it a real full-screen viewport so Spotify's responsive web player
            // renders its full DOM incl. the Play button — a tiny/0-size view
            // collapses the layout (innerWidth=0) and the controls never get
            // created, and the track page won't even navigate. MATCH_PARENT gives a
            // genuine size; translate it off-screen so it's invisible but attached
            // (attachment is required for audio to play).
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            activity.addContentView(wv, params)
            wv.post { wv.translationX = wv.width.toFloat() + 2000f }
            wv.loadUrl("https://open.spotify.com/", ENGLISH_HEADERS)
            webView = wv
            pollHandler.removeCallbacks(pollRunnable)
            pollHandler.postDelayed(pollRunnable, 1000)
            Log.d(TAG, "WebView attached")
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    /**
     * Re-load the player with the now-current login. The hidden player WebView is
     * created at app startup — on a fresh install that's BEFORE the user logs in,
     * so it's stuck with a logged-out session (→ "Oops"). Call this the moment
     * login succeeds so the player picks up the new sp_dc session.
     */
    fun refreshLogin(context: Context) {
        val spDc = com.music.spotui.data.api.SpotifySession.spDc(context)
        if (spDc.isBlank()) return
        val wv = webView
        if (wv == null) {
            if (context is Activity) {
                attach(context)
            }
            return
        }
        CookieManager.getInstance().apply {
            val attrs = "Domain=.spotify.com; Path=/; Secure"
            setCookie("https://open.spotify.com", "sp_dc=$spDc; $attrs")
            setCookie("https://spotify.com", "sp_dc=$spDc; $attrs")
            flush()
        }
        commandReady = false
        activated = false
        wv.post { wv.loadUrl("https://open.spotify.com/", ENGLISH_HEADERS) }
        Log.d(TAG, "refreshLogin: reloaded player with logged-in session")
    }

    fun play(trackId: String) = playUri("spotify:track:$trackId", "track/$trackId")

    /** Play a podcast episode (same engine as tracks). */
    fun playEpisode(episodeId: String) = playUri("spotify:episode:$episodeId", "episode/$episodeId")

    /**
     * Play a Spotify uri. Once the web player is initialized (commandReady), switch
     * tracks via the connect-state command API — NO page reload, which is what
     * caused the frequent "Oops, something went wrong". Before it's ready, do the
     * one-time navigation (which also boots the player + captures its tokens).
     */
    private fun playUri(uri: String, path: String) {
        val wv = webView ?: run { Log.w(TAG, "play() before attach"); return }
        Log.d(TAG, "playUri($uri) activated=$activated commandReady=$commandReady")
        wv.post {
            if (activated && commandReady) {
                sendCommand(uri, path)
            } else {
                navigateAndPlay(wv, uri, path)
                activated = true
            }
        }
    }

    private fun sendCommand(uri: String, path: String? = null) {
        val wv = webView ?: return
        wv.evaluateJavascript("(window.__spotuiPlay ? window.__spotuiPlay('$uri') : 'no-fn');") { r ->
            Log.d(TAG, "command play -> $r")
            val res = r?.trim('"')
            if ((res == "no-fn" || res == "no-device") && path != null) {
                wv.post { navigateAndPlay(wv, uri, path) }
            }
        }
    }

    /** Last-resort cold start: navigate once to boot the player, then click play. */
    private fun navigateAndPlay(wv: WebView, uri: String, path: String) {
        wv.loadUrl("https://open.spotify.com/$path", ENGLISH_HEADERS)
        lastClickResult = null
        val startedAt = System.currentTimeMillis()
        var lastSentAt = 0L
        var clicked = false
        var dialogTries = 0
        val tick = object : Runnable {
            override fun run() {
                if (webView !== wv || isPlaying) return
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed > NAVIGATE_PLAY_WINDOW_MS) return
                if (dialogTries < MAX_DIALOG_TRIES) {
                    dialogTries++
                    wv.evaluateJavascript("(function(){ $PLAYER_STATE_JS return spotuiDismissDialog() || ''; })();", null)
                }
                wv.evaluateJavascript("(window.__devId && window.__auth) ? '1' : '0'") { ready ->
                    if (webView !== wv || isPlaying) return@evaluateJavascript
                    val now = System.currentTimeMillis()
                    if (ready?.contains("1") == true && (lastSentAt == 0L || now - lastSentAt > COMMAND_RESEND_MS)) {
                        lastSentAt = now
                        wv.evaluateJavascript("(window.__spotuiPlay ? window.__spotuiPlay('$uri') : 'no-fn');") { r ->
                            lastClickResult = "command:" + r
                        }
                    } else if (!clicked && elapsed > CLICK_FALLBACK_MS && ready?.contains("1") != true) {
                        clicked = true
                        wv.evaluateJavascript(clickPlayJs()) { r -> lastClickResult = r }
                    }
                    wv.postDelayed(this, 500)
                }
            }
        }
        wv.postDelayed(tick, 1500)
    }

    @Volatile private var probeCallback: ((String) -> Unit)? = null

    fun probe(onResult: (String) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult("no webview")
            return
        }
        probeCallback = { json ->
            probeCallback = null
            onResult(json)
        }
        wv.post { wv.evaluateJavascript("(window.__spotuiProbe ? window.__spotuiProbe() : 'no-probe');", null) }
    }

    fun diagnose(onResult: (String) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult("no webview")
            return
        }
        wv.post {
            wv.evaluateJavascript(diagnosticJs()) { r ->
                val text = runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull() ?: r.orEmpty()
                onResult(text)
            }
        }
    }

    private fun diagnosticJs() = """
        (function(){
          $PLAYER_STATE_JS
          function q(s){ return document.querySelector(s); }
          var st = spotuiState();
          var dur = q('[data-testid="playback-duration"]');
          var now = q('[data-testid="now-playing-widget"]');
          var body = (document.body && document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 140);
          return JSON.stringify({
            url: location.pathname,
            vis: document.visibilityState,
            label: st.label,
            session: st.session,
            media: st.media ? [st.media.paused, Math.round(st.media.currentTime), Math.round(st.media.duration || 0)] : null,
            mediaCount: st.count,
            tracked: (window.__spotuiMedia || []).length,
            icon: st.icon,
            hook: !!window.__spotuiAdHook,
            reg: window.__spotuiReg || null,
            devShort: String(window.__devId || '').slice(0, 10),
            workers: window.__spotuiWorkers || null,
            boot: !!window.__spotuiReady,
            active: !!window.__spotuiActive,
            ws: window.__spotuiWs || null,
            devId: !!window.__devId,
            auth: !!window.__auth,
            lang: document.documentElement.lang,
            errs: window.__spotuiErrors || [],
            dialogText: (function(){ var d = q('[role="dialog"]'); return d ? (d.innerText || '').replace(/\s+/g, ' ').slice(0, 160) : null; })(),
            playBtn: !!q('[data-testid="play-button"]'),
            dur: dur ? dur.textContent : null,
            now: now ? (now.innerText || '').replace(/\s+/g, ' ').slice(0, 60) : null,
            loginBtn: !!(q('[data-testid="login-button"]') || q('[data-testid="signup-button"]')),
            dialog: !!q('[role="dialog"]'),
            body: body
          });
        })();
    """.trimIndent()

    fun resume() = eval(clickPlayJs())
    fun pause() = eval(pauseJs())
    fun next() = eval(clickJs("control-button-skip-forward"))
    fun previous() = eval(clickJs("control-button-skip-back"))

    /** Seek the web player to an absolute position (ms) by driving its progress bar. */
    fun seekTo(positionMs: Long) {
        val wv = webView ?: return
        val dur = durationMs
        if (dur <= 0) return
        val frac = (positionMs.toDouble() / dur).coerceIn(0.0, 1.0)
        wv.post { wv.evaluateJavascript(seekJs(frac)) { r -> Log.d(TAG, "seek ${"%.3f".format(frac)} -> $r") } }
        // Optimistic UI update; the 500ms poll will correct it from the real bar.
        this.positionMs = positionMs.coerceIn(0, dur)
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
    }

    private fun eval(js: String) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js, null) }
    }

    private fun chromeVersion(context: Context): String =
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }
            .getOrNull()
            ?.takeIf { it.matches(Regex("""\d+(\.\d+)+""")) }
            ?: FALLBACK_CHROME_VERSION

    private fun desktopUserAgent(version: String): String {
        val major = version.substringBefore('.')
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private fun applyBrowserIdentity(wv: WebView, version: String) {
        val major = version.substringBefore('.')
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, majorVersion: String, full: String) =
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(name)
                    .setMajorVersion(majorVersion)
                    .setFullVersion(full)
                    .build()
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        brand("Not;A=Brand", "8", "8.0.0.0"),
                        brand("Chromium", major, version),
                        brand("Google Chrome", major, version),
                    ),
                )
                .setFullVersion(version)
                .setPlatform("Windows")
                .setPlatformVersion("10.0.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
        }
    }

    private fun installDocumentStartScripts(wv: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                wv,
                VISIBILITY_SPOOF_JS + "\n" + LOCALE_SPOOF_JS + "\n" + MEDIA_TRACK_JS + "\n" + AD_STATE_HOOK_JS + "\n" + BOOTSTRAP_JS,
                setOf(SPOTIFY_ORIGIN),
            )
            true
        }.getOrDefault(false)
    }

    private fun recordAdIds(json: String) {
        val parsed = runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }
        }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return
        synchronized(adIds) {
            adIds.addAll(parsed)
            while (adIds.size > MAX_AD_IDS) adIds.remove(adIds.first())
        }
        Log.d(TAG, "adblock: tracking ${parsed.size} ad content ids")
    }

    private fun matchesAdId(url: String): Boolean = synchronized(adIds) {
        adIds.isNotEmpty() && adIds.any { it in url }
    }

    private val AD_STATE_HOOK_JS = """
        (function(){
          if (window.__spotuiAdHook) return;
          window.__spotuiAdHook = 1;
          var MAX = $MAX_AD_IDS, ids = [], seen = {}, dirty = false, budget = 0;
          var ID_RE = /^[A-Za-z0-9_-]{8,128}${'$'}/;
          function add(v){
            if (typeof v !== 'string' || !ID_RE.test(v) || seen[v]) return;
            seen[v] = 1; ids.push(v); dirty = true;
            while (ids.length > MAX) delete seen[ids.shift()];
          }
          function flagged(o){
            var m = o.metadata || {};
            var c = [o.content_type, o.contentType, o.type, o.is_ad, o.isAd, m.content_type, m.is_advertisement, m.is_ad, m.isAd];
            for (var i = 0; i < c.length; i++) {
              var v = c[i];
              if (v === true) return true;
              if (typeof v === 'string') {
                var u = v.toUpperCase();
                if (u === 'AD' || u === 'ADVERTISEMENT' || u === 'TRUE') return true;
              }
            }
            return false;
          }
          function scan(node, ad, depth){
            if (!node || typeof node !== 'object' || depth > 14 || budget-- <= 0) return;
            var isAd = ad || (!Array.isArray(node) && flagged(node));
            for (var k in node) {
              var v = node[k];
              if (isAd && /file_?ids?/i.test(k)) {
                if (typeof v === 'string') add(v);
                else if (Array.isArray(v)) v.forEach(function(e){
                  if (typeof e === 'string') add(e);
                  else if (e) { add(e.file_id); add(e.fileId); add(e.id); }
                });
              }
              if (v && typeof v === 'object') scan(v, isAd, depth + 1);
            }
          }
          function publish(){
            if (!dirty) return;
            dirty = false;
            try { SpotuiBridge.onAdIds(JSON.stringify(ids)); } catch(e){}
          }
          function inspect(obj){ budget = 6000; scan(obj, false, 0); publish(); }
          function gunzip(b64){
            var bin = atob(b64), bytes = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
            var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
            return new Response(stream).text();
          }
          function tapState(msg){
            try {
              if (!msg || msg.uri !== 'hm://connect-state/v1/cluster' || !msg.payloads || !msg.payloads.length) return;
              var p = msg.payloads[0];
              if (typeof p === 'string') p = JSON.parse(p);
              var c = p && p.cluster, st = c && c.player_state;
              if (!st) return;
              window.__spotuiActive = !!window.__devId && c.active_device_id === window.__devId;
              var tr = st.track || {};
              var md = tr.metadata || {};
              SpotuiBridge.onPlayerState(
                c.active_device_id || '', window.__devId || '',
                !!st.is_playing && !st.is_paused,
                Number(st.position_as_of_timestamp) || 0, Number(st.duration) || 0,
                Number(st.timestamp) || 0, Number(c.server_timestamp_ms) || 0,
                tr.uri || '', md.linked_from_uri || ''
              );
            } catch(e){}
          }
          function handleMessage(text){
            if (typeof text !== 'string' || text.length > 8000000) return;
            var parsed;
            try { parsed = JSON.parse(text); } catch(e){ return; }
            inspect(parsed);
            tapState(parsed);
            var gz = parsed && parsed.headers && /gzip/i.test(parsed.headers['Transfer-Encoding'] || '');
            if (gz && parsed.payloads && parsed.payloads.length && typeof DecompressionStream === 'function') {
              parsed.payloads.forEach(function(p){
                if (typeof p !== 'string') { inspect(p); return; }
                gunzip(p).then(function(t){ try { inspect(JSON.parse(t)); } catch(e){} }).catch(function(){});
              });
            }
          }
          try {
            var origFetch = window.fetch;
            window.fetch = function(){
              var p = origFetch.apply(this, arguments);
              try {
                var a = arguments[0];
                var u = String(a && a.url ? a.url : a);
                if (/state|connect-state|playback/i.test(u)) {
                  p.then(function(r){ try { r.clone().json().then(inspect).catch(function(){}); } catch(e){} }).catch(function(){});
                }
              } catch(e){}
              return p;
            };
          } catch(e){}
          try {
            ['Worker', 'SharedWorker'].forEach(function(name){
              var Orig = window[name];
              if (!Orig) return;
              window[name] = class extends Orig {
                constructor(url, opts){
                  super(url, opts);
                  try { (window.__spotuiWorkers = window.__spotuiWorkers || []).push(name + ':' + String(url).slice(0, 70)); } catch(e){}
                }
              };
            });
          } catch(e){}
          try {
            var OrigWS = window.WebSocket;
            var Wrapped = class extends OrigWS {
              constructor(url, protocols){
                if (protocols === undefined) super(url); else super(url, protocols);
                var info = window.__spotuiWs = window.__spotuiWs || { opened: 0, closed: 0, errors: 0, msgs: 0, last: '', uris: [] };
                try { info.host = String(url).replace(/^wss?:\/\/([^\/?]+).*/, '$1'); } catch(e){}
                this.addEventListener('open', function(){ info.opened++; });
                this.addEventListener('close', function(ev){ info.closed++; info.last = 'close:' + ev.code; });
                this.addEventListener('error', function(){ info.errors++; });
                this.addEventListener('message', function(ev){
                  info.msgs++;
                  if (!info.connId && typeof ev.data === 'string') {
                    var cm = /Spotify-Connection-Id"\s*:\s*"([^"]{0,10})/.exec(ev.data.slice(0, 1500));
                    if (cm) info.connId = cm[1];
                  }
                  if (!info.samples) info.samples = [];
                  if (info.samples.length < 3) {
                    var raw = typeof ev.data === 'string' ? ev.data.slice(0, 80).replace(/[A-Za-z0-9_-]{24,}/g, '<id>') : '[' + Object.prototype.toString.call(ev.data) + ']';
                    info.samples.push((typeof ev.data) + ':' + (ev.data && (ev.data.length || ev.data.size || ev.data.byteLength)) + ':' + raw);
                  }
                  if (typeof ev.data === 'string') {
                    var m = /"uri"\s*:\s*"([^"]{0,60})"/.exec(ev.data.slice(0, 20000));
                    if (m) { info.uris.push(m[1]); if (info.uris.length > 4) info.uris.shift(); }
                    handleMessage(ev.data);
                  }
                });
              }
            };
            window.WebSocket = Wrapped;
          } catch(e){}
        })();
    """.trimIndent()

    // Force the page to always look visible + focused. The hidden off-screen
    // WebView otherwise reports document.hidden=true, and Spotify's Web Playback
    // SDK won't register its Connect device or start audio on a hidden page (this
    // is why Spotify Connect stopped showing "Web Player" and playback went silent).
    private val VISIBILITY_SPOOF_JS = """
        (function(){
          try {
            var vis = function(){ return 'visible'; };
            var no  = function(){ return false; };
            Object.defineProperty(document,'visibilityState',{configurable:true,get:vis});
            Object.defineProperty(document,'hidden',{configurable:true,get:no});
            Object.defineProperty(document,'webkitVisibilityState',{configurable:true,get:vis});
            Object.defineProperty(document,'webkitHidden',{configurable:true,get:no});
            document.hasFocus = function(){ return true; };
            // Swallow visibilitychange/blur so the SDK never sees us go background.
            ['visibilitychange','webkitvisibilitychange','blur','pagehide'].forEach(function(t){
              window.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
              document.addEventListener(t, function(e){ e.stopImmediatePropagation(); }, true);
            });
          } catch(e){}
        })();
    """.trimIndent()

    // Injected once per page load. Hooks fetch to capture the web player's device
    // id + auth/client tokens (from its own requests), then exposes
    // __spotuiPlay(uri) which plays any track/episode via Spotify's connect-state
    // command API — no page reload. license:'tft' matches SpotiFuck. Also reloads
    // on a connect-state 404 (player lock) to self-heal.
    private val BOOTSTRAP_JS = """
        (function(){
          if (window.__spotuiReady) return;
          window.__spotuiReady = true;
          window.__featVer = 'web-player_' + Date.now();
          var oriFetch = window.fetch.bind(window);
          window.__oriFetch = oriFetch;
          // PURELY OBSERVATIONAL: read the player's own requests to capture its
          // device id, tokens and region-correct spclient origin. Never alters a
          // request and never reloads — the old reload-on-404 destabilised the
          // half-initialised player and caused the "Oops" loop.
          window.fetch = function(){
            try {
              var url = arguments[0], opts = arguments[1] || {};
              var us = (typeof url === 'string') ? url : (url && url.url) || '';
              var hs = opts.headers || {};
              function hv(n){ try { return hs.get ? hs.get(n) : (hs[n] || hs[n.toLowerCase()]); } catch(e){ return null; } }
              var m = us.match(/https?:\/\/([a-z0-9-]*spclient[a-z0-9.\-]*)/i);
              if (m) window.__spBase = 'https://' + m[1];
              if (us.indexOf('/track-playback/v1/devices') > -1 && opts.body) {
                try { var b = JSON.parse(opts.body); if (b && b.device && b.device.device_id) window.__devId = b.device.device_id; } catch(e){}
              }
              var ct = hv('Client-Token'); if (ct) window.__cliToken = ct;
              var ap = hv('app-platform'); if (ap) window.__appPlatform = ap;
              var av = hv('spotify-app-version'); if (av) window.__appVer = av;
              var au = hv('Authorization'); if (au && au.indexOf('Bearer') === 0) window.__auth = au;
              if (window.__devId && window.__auth && !window.__reported) {
                window.__reported = true;
                try { SpotuiBridge.onCommandReady(); } catch(e){}
              }
            } catch(e){}
            var pending = oriFetch.apply(this, arguments);
            try {
              var target = (typeof arguments[0] === 'string') ? arguments[0] : (arguments[0] && arguments[0].url) || '';
              var isTp = target.indexOf('/track-playback/v1/devices') > -1;
              var isCs = target.indexOf('/connect-state/v1/devices/') > -1;
              if (isTp || isCs) {
                var verb = ((arguments[1] && arguments[1].method) || (arguments[0] && arguments[0].method) || 'GET').toUpperCase();
                var key = (isCs ? 'cs:' : 'tp:') + verb;
                var reg = window.__spotuiReg = window.__spotuiReg || {};
                var hs2 = (arguments[1] && arguments[1].headers) || {};
                function hv2(n){ try { return hs2.get ? hs2.get(n) : (hs2[n] || hs2[n.toLowerCase()]); } catch(e){ return null; } }
                if (isCs && verb === 'PUT') reg['cs:conn'] = String(hv2('X-Spotify-Connection-Id') || 'none').slice(0, 10);
                pending.then(function(r){
                  r.clone().text().then(function(t){
                    reg[key] = r.status + (r.ok ? '' : ' ' + t.replace(/\s+/g, ' ').slice(0, 80));
                    if (isCs && verb === 'PUT' && r.ok) {
                      try {
                        var j = JSON.parse(t);
                        reg['cs:cluster'] = (Object.keys(j.devices || {}).map(function(k){ return k.slice(0, 10); }).join(',') || 'nodevs') + ' active=' + String(j.active_device_id || '').slice(0, 10);
                      } catch(e){ reg['cs:cluster'] = 'unparsed:' + t.length; }
                    }
                  }).catch(function(){});
                }).catch(function(e){ reg[key] = 'err ' + String(e).slice(0, 60); });
              }
            } catch(e){}
            return pending;
          };
          // Only ever called AFTER the token+device are captured (native waits for
          // onCommandReady), so we never poke a half-initialised player.
          window.__spotuiProbe = function(){
            var out = {};
            function get(url){
              return window.__oriFetch(url, { headers: { 'Authorization': window.__auth } })
                .then(function(r){ return r.text().then(function(t){ return { s: r.status, t: t }; }); })
                .catch(function(e){ return { s: -1, t: String(e) }; });
            }
            get('https://api.spotify.com/v1/me/player').then(function(a){
              try {
                var j = JSON.parse(a.t);
                out.player = { s: a.s, playing: j.is_playing, dev: j.device && (j.device.name + '/' + String(j.device.id).slice(0, 6)), uri: j.item && j.item.uri, prog: j.progress_ms, dur: j.item && j.item.duration_ms };
              } catch(e) { out.player = { s: a.s, body: String(a.t).slice(0, 60) }; }
              return get('https://api.spotify.com/v1/me/player/devices');
            }).then(function(b){
              try {
                var j = JSON.parse(b.t);
                out.devices = (j.devices || []).map(function(d){ return d.name + (d.is_active ? '*' : '') + '/' + String(d.id).slice(0, 6); });
              } catch(e) { out.devices = { s: b.s, body: String(b.t).slice(0, 60) }; }
              try { SpotuiBridge.onProbe(JSON.stringify(out)); } catch(e){}
            });
            return 'probing';
          };
          window.__spotuiPlay = function(uri){
            if (!window.__devId || !window.__auth) return 'no-device';
            var base = (window.__spBase || 'https://gew4-spclient.spotify.com') + '/connect-state/v1/';
            var dev = window.__devId;
            var type = (uri.match(/^spotify:([^:]+)/) || [])[1] || 'track';
            function rnd(){ var s = ''; for (var i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16); return s; }
            var headers = { 'Authorization': window.__auth, 'Client-Token': window.__cliToken || '', 'Content-Type': 'application/json', 'app-platform': window.__appPlatform || 'WebPlayer' };
            if (window.__appVer) headers['spotify-app-version'] = window.__appVer;
            function post(path, body, tag){
              return window.__oriFetch(base + path, { method: 'POST', headers: headers, body: JSON.stringify(body) })
                .then(function(r){ return r.text().then(function(t){ try { SpotuiBridge.onCommandResult(r.status, tag + ' ' + t.replace(/\s+/g, ' ')); } catch(e){} return r.status; }); })
                .catch(function(e){ try { SpotuiBridge.onCommandResult(-1, tag + ' ' + String(e)); } catch(x){} return -1; });
            }
            function play(){
              return post('player/command/from/' + dev + '/to/' + dev, { command: { context: { uri: uri, url: 'context://' + uri, metadata: {} }, play_origin: { feature_identifier: type, feature_version: window.__featVer, referrer_identifier: 'search' }, options: { license: 'tft', skip_to: {}, player_options_override: {} }, endpoint: 'play', logging_params: { page_instance_ids: [], interaction_ids: [], command_id: rnd() } } }, 'play');
            }
            if (window.__spotuiActive) { play(); return 'sent'; }
            post('connect/transfer/from/' + dev + '/to/' + dev, { transfer_options: { restore_paused: 'pause' }, command_id: rnd() }, 'transfer')
              .then(function(){ setTimeout(play, 1500); });
            return 'transfer+play';
          };
        })();
    """.trimIndent()

    // Start playback of the currently-open page. Prefer the big context Play
    // button (track/album/playlist page), then the now-playing transport, then
    // any button whose label starts with "Play". Never re-click when already
    // playing (label would say "Pause"). Returns 'clicked …' or 'no-play-button'.
    private val LOCALE_SPOOF_JS = """
        (function(){
          function def(o, k, g){ try { Object.defineProperty(o, k, { get: g, configurable: true }); } catch (e) {} }
          def(navigator, 'language', function(){ return 'en-US'; });
          def(navigator, 'languages', function(){ return ['en-US', 'en']; });
          if (!window.__spotuiErrors) {
            window.__spotuiErrors = [];
            window.addEventListener('error', function(e){
              window.__spotuiErrors.push(String(e.message || e).slice(0, 80));
              if (window.__spotuiErrors.length > 3) window.__spotuiErrors.shift();
            });
            window.addEventListener('unhandledrejection', function(e){
              window.__spotuiErrors.push('rej:' + String(e.reason && e.reason.message || e.reason).slice(0, 80));
              if (window.__spotuiErrors.length > 3) window.__spotuiErrors.shift();
            });
          }
        })();
    """.trimIndent()

    private val MEDIA_TRACK_JS = """
        (function(){
          if (window.__spotuiMedia) return;
          var tracked = [];
          window.__spotuiMedia = tracked;
          var origPlay = HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play = function(){
            if (tracked.indexOf(this) < 0) tracked.push(this);
            return origPlay.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private val PLAYER_STATE_JS = """
        function spotuiState(){
          var pp = document.querySelector('[data-testid="control-button-playpause"]');
          var label = pp ? (pp.getAttribute('aria-label') || '').toLowerCase() : '';
          var session = (navigator.mediaSession && navigator.mediaSession.playbackState) || '';
          var media = [].slice.call(document.querySelectorAll('audio,video'));
          (window.__spotuiMedia || []).forEach(function(m){ if (media.indexOf(m) < 0) media.push(m); });
          var active = null;
          for (var i = 0; i < media.length; i++) {
            var m = media[i];
            if (!m.paused && !m.ended && m.readyState > 1) { active = m; break; }
          }
          var now = document.querySelector('[data-testid="now-playing-widget"]');
          var durEl = document.querySelector('[data-testid="playback-duration"]');
          var durOk = !!(durEl && /\d/.test(durEl.textContent || ''));
          var byLabel = /pause|pozastav|pausa|pausar|pausier|pauze|paus/.test(label);
          var icon = pp ? pp.querySelector('svg path') : null;
          var iconPath = icon ? (icon.getAttribute('d') || '') : '';
          var iconState = iconPath.indexOf('M2.7 1a.7.7') === 0 ? true : (iconPath.indexOf('M3 1.713') === 0 ? false : null);
          var domPlaying = iconState !== null ? iconState : byLabel;
          return { pp: pp, label: label, session: session, media: active, count: media.length, icon: iconState,
                   playing: session === 'playing' || !!active || (domPlaying && !!now && durOk) };
        }
        function spotuiDismissDialog(){
          var d = document.querySelector('[role="dialog"]');
          if (!d) return null;
          var close = d.querySelector('[data-testid="close-button"],[data-testid="modal-close"],button[aria-label*="lose" i],button[aria-label*="avřít" i],button[aria-label*="chlie" i],button[aria-label*="errar" i],button[aria-label*="ermer" i],button[aria-label*="hiudi" i]');
          var attempt = (window.__spotuiDlgTry = ((window.__spotuiDlgTry || 0) + 1) % 3);
          if (attempt === 1 && close) { close.click(); return 'dialog-closed'; }
          if (attempt === 0) {
            var esc = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true });
            document.dispatchEvent(esc);
            return 'dialog-escape';
          }
          var lang = (document.documentElement.lang || navigator.language || 'en').split('-')[0];
          var names = [];
          try { names.push(new Intl.DisplayNames([lang], { type: 'language' }).of(lang)); } catch (e) {}
          names.push('English');
          var kinds = ['button', '[role="button"]', '[role="option"]', 'a', 'li'];
          for (var n = 0; n < names.length; n++) {
            var nm = (names[n] || '').toLowerCase();
            if (!nm) continue;
            for (var k = 0; k < kinds.length; k++) {
              var items = [].slice.call(d.querySelectorAll(kinds[k]));
              for (var i = 0; i < items.length; i++) {
                var t = (items[i].textContent || '').toLowerCase();
                if (t.indexOf(nm) > -1 && t.length < nm.length * 3) { items[i].click(); return 'dialog-language:' + nm + ':' + kinds[k]; }
              }
            }
          }
          var ev = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true });
          document.dispatchEvent(ev);
          return 'dialog-escape';
        }
    """.trimIndent()

    private fun clickPlayJs() =
        """
        (function(){
          $PLAYER_STATE_JS
          var st = spotuiState();
          if (st.playing) return 'already-playing';
          var dismissed = spotuiDismissDialog();
          if (dismissed) return dismissed;
          var b = document.querySelector('[data-testid="play-button"]');
          if (b) { b.click(); return 'clicked'; }
          if (st.pp) { st.pp.click(); return 'clicked-transport'; }
          return 'no-play-button';
        })();
        """.trimIndent()

    private fun pauseJs() =
        """
        (function(){
          $PLAYER_STATE_JS
          var st = spotuiState();
          if (st.playing && st.pp) { st.pp.click(); return 'paused'; }
          return 'not-playing';
        })();
        """.trimIndent()

    private fun clickJs(testId: String) =
        """
        (function(){ var b=document.querySelector('[data-testid="$testId"]');
          if(b){b.click();return 'clicked';} return 'missing'; })();
        """.trimIndent()

    // Seek to a fraction [0..1] of the track. The desktop web player's progress bar
    // is a React-controlled <input type=range>; set its value via the native setter
    // (so React's onChange fires) then dispatch input/change. Falls back to
    // simulating a click at the fraction's x on the bar element.
    private fun seekJs(frac: Double) =
        """
        (function(){
          var f=$frac;
          var input=document.querySelector('[data-testid="playback-progressbar"] input[type="range"]')
                 || document.querySelector('[data-testid="progress-bar"] input[type="range"]')
                 || document.querySelector('div[data-testid="playback-progressbar"] input');
          if(input){
            var max=parseFloat(input.max)||1, val=f*max;
            var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
            setter.call(input, String(val));
            input.dispatchEvent(new Event('input',{bubbles:true}));
            input.dispatchEvent(new Event('change',{bubbles:true}));
            return 'input '+val.toFixed(1)+'/'+max;
          }
          var bar=document.querySelector('[data-testid="playback-progressbar"]')
               || document.querySelector('[data-testid="progress-bar"]');
          if(!bar) return 'no-bar';
          var r=bar.getBoundingClientRect();
          var x=r.left+Math.max(0,Math.min(1,f))*r.width, y=r.top+r.height/2;
          ['pointerdown','pointerup','click'].forEach(function(t){
            bar.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y}));
          });
          return 'clicked-bar';
        })();
        """.trimIndent()

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

        if (matchesAdId(url)) {
            Log.d(TAG, "adblock: silenced ad content by id $url")
            return runCatching {
                android.webkit.WebResourceResponse(
                    "audio/mpeg", null, context.assets.open("silent.mp3"),
                )
            }.getOrNull()
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

    // Read the now-playing bar's position/duration text ("M:SS") + play state.
    // Returns "posSec|durSec|playing" (fields -1 / 0 when unknown).
    private fun progressJs() =
        """
        (function(){
          $PLAYER_STATE_JS
          function t2s(t){ if(!t) return -1; var p=t.trim().split(':');
            if(!p.length) return -1; var s=0;
            for(var i=0;i<p.length;i++){ var n=parseInt(p[i],10); if(isNaN(n)) return -1; s=s*60+n; }
            return s; }
          var pe=document.querySelector('[data-testid="playback-position"]');
          var de=document.querySelector('[data-testid="playback-duration"]');
          var st=spotuiState();
          var pos=t2s(pe&&pe.textContent), dur=t2s(de&&de.textContent);
          if (st.media) {
            if (dur<0 && isFinite(st.media.duration) && st.media.duration>0) dur=Math.round(st.media.duration);
            if (pos<0) pos=Math.floor(st.media.currentTime);
          }
          return pos+'|'+dur+'|'+(st.playing?'1':'0');
        })();
        """.trimIndent()
}
