package com.music.spotui.deezer

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Deezer gw-light / media API session. A Kotlin port of the networking half of
 * ReFreezer's `Deezer.java` (github.com/DJDoubleD/refreezer): authorise with an
 * ARL cookie, resolve a track's stream token, and mint a CDN stream URL.
 *
 * All calls are blocking (java.net) and meant to run off the main thread. The
 * object holds a single session; [setArl] resets it when the account changes.
 */
internal object DeezerSession {

    private const val TAG = "DeezerSession"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/79.0.3945.130 Safari/537.36"

    // Deezer quality ids (match ReFreezer): 9 = FLAC, 3 = MP3 320, 1 = MP3 128.
    const val QUALITY_FLAC = 9
    const val QUALITY_MP3_320 = 3
    const val QUALITY_MP3_128 = 1

    @Volatile private var arl: String = ""
    @Volatile private var token: String? = null
    @Volatile private var sid: String? = null
    @Volatile private var licenseToken: String? = null
    @Volatile private var authorized = false

    /** Highest quality the signed-in account is entitled to (9/3/1). */
    @Volatile var entitledQuality: Int = QUALITY_MP3_128
        private set
    /** Whether the signed-in account is a paid (HQ/lossless) tier. */
    val isPremium: Boolean get() = entitledQuality > QUALITY_MP3_128

    /** Point the session at a new ARL, dropping any cached auth. */
    @Synchronized
    fun setArl(newArl: String) {
        if (newArl == arl && authorized) return
        arl = newArl
        token = null
        sid = null
        licenseToken = null
        authorized = false
        entitledQuality = QUALITY_MP3_128
    }

    fun hasArl(): Boolean = arl.isNotBlank()

    /** Ensure the session is authorised. Safe to call repeatedly. */
    @Synchronized
    fun authorize() {
        if (authorized && sid != null && token != null) return
        runCatching { callGwApi("deezer.getUserData", "{}") }
            .onFailure { Log.w(TAG, "Deezer authorize failed: $it") }
    }

    // ── Track resolution ─────────────────────────────────────────────────────

    data class TrackTokens(
        val id: String,
        val trackToken: String,
        val md5origin: String,
        val mediaVersion: String,
        /**
         * Deezer's replacement track id (`FALLBACK.SNG_ID`) for tracks that are blocked in the
         * account's country / not streamable; null when there is none.
         */
        val fallbackId: String? = null,
    )

    /** Result of minting a stream URL; [error] explains a failure (Deezer error code + message). */
    data class UrlResult(val url: String?, val encrypted: Boolean, val error: String? = null)

    /** Resolve a Deezer track id from an ISRC via the public API. */
    fun deezerIdForIsrc(isrc: String): String? = runCatching {
        val json = callPublicApi("track/isrc:${isrc.trim().uppercase()}")
        json.optString("id").takeIf { it.isNotBlank() && !json.has("error") }
    }.getOrNull()

    /** Fallback lookup: best Deezer track match for a free-text "title artist". */
    fun searchTrackId(query: String): String? = runCatching {
        val enc = URLEncoder.encode(query, "UTF-8")
        val json = callPublicApi("search/track?q=$enc&limit=1")
        json.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Fetch the private stream token + CDN origin for a Deezer track id. */
    fun trackTokens(deezerId: String): TrackTokens? = trackTokensOrError(deezerId).first

    /** Like [trackTokens], but also returns why the lookup failed (null error on success). */
    fun trackTokensOrError(deezerId: String): Pair<TrackTokens?, String?> = try {
        authorize()
        val results = callGwApi("song.getListData", "{\"sng_ids\": [$deezerId]}")
            .getJSONObject("results")
        val data = results.getJSONArray("data").getJSONObject(0)
        val sngId = data.getString("SNG_ID")
        TrackTokens(
            id = sngId,
            trackToken = data.getString("TRACK_TOKEN"),
            md5origin = data.optString("MD5_ORIGIN"),
            mediaVersion = data.optString("MEDIA_VERSION"),
            fallbackId = data.optJSONObject("FALLBACK")
                ?.optString("SNG_ID")
                ?.takeIf { it.isNotBlank() && it != "0" && it != sngId },
        ) to null
    } catch (e: Exception) {
        null to "${e.javaClass.simpleName}: ${e.message?.take(120)}"
    }

    /**
     * Mint a CDN stream URL for [tokens] at [quality]. Returns the url and whether
     * the stream is Blowfish-encrypted (media get_url streams always are).
     * Ported from `Deezer.getTrackUrl` incl. the token-refresh retry.
     */
    fun getTrackUrl(tokens: TrackTokens, quality: Int, refreshAttempt: Int = 0): Pair<String?, Boolean> =
        mintTrackUrl(tokens, quality, refreshAttempt).let { it.url to it.encrypted }

    /** Same as [getTrackUrl] but keeps Deezer's error code/message when no URL can be minted. */
    fun mintTrackUrl(tokens: TrackTokens, quality: Int, refreshAttempt: Int = 0): UrlResult {
        val lt = licenseToken
        if (lt != null && quality > 0) {
            val format = when (quality) {
                QUALITY_MP3_320 -> "MP3_320"
                QUALITY_MP3_128 -> "MP3_128"
                else -> "FLAC"
            }
            var failure: String? = null
            try {
                val payload = """
                    {"license_token": "$lt",
                     "media": [{"type": "FULL", "formats": [{"cipher": "BF_CBC_STRIPE", "format": "$format"}]}],
                     "track_tokens": ["${tokens.trackToken}"]}
                """.trimIndent()
                val output = post(
                    "https://media.deezer.com/v1/get_url",
                    payload,
                    mapOf("Cookie" to "arl=$arl"),
                ) ?: return UrlResult(null, true, "$format: empty get_url response")
                val result = JSONObject(output)
                if (result.has("data")) {
                    val arr = result.getJSONArray("data")
                    for (i in 0 until arr.length()) {
                        val data = arr.getJSONObject(i)
                        if (data.has("errors")) {
                            val errors = data.getJSONArray("errors")
                            val summary = mutableListOf<String>()
                            for (j in 0 until errors.length()) {
                                val err = errors.getJSONObject(j)
                                val code = err.optInt("code")
                                summary += "$code ${err.optString("message")}".trim()
                                if (code == 2001 && refreshAttempt < 1) {
                                    // Expired track token — refresh once and retry.
                                    trackTokens(tokens.id)?.let { fresh ->
                                        return mintTrackUrl(fresh, quality, refreshAttempt + 1)
                                    }
                                }
                            }
                            failure = "$format: ${summary.joinToString("; ")}"
                            Log.w(TAG, "get_url errors: ${data.get("errors")}")
                        }
                        val media = data.optJSONArray("media")
                        if (media != null && media.length() > 0) {
                            val url = media.getJSONObject(0)
                                .getJSONArray("sources").getJSONObject(0).getString("url")
                            return UrlResult(url, true)
                        }
                    }
                } else if (result.has("error")) {
                    failure = "$format: ${result.opt("error")}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "get_url failed: $e")
                failure = "$format: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
            return UrlResult(null, true, failure ?: "$format: no media in get_url response")
        }
        // Legacy CDN generation (kept as last resort, mostly dead on modern Deezer).
        val legacy = generateTrackUrl(tokens, quality)
        return UrlResult(
            legacy,
            true,
            if (legacy == null) "no license token (not logged in?) and legacy url generation failed" else null,
        )
    }

    // ── HTTP + auth internals ────────────────────────────────────────────────

    @Synchronized
    private fun callGwApi(method: String, body: String): JSONObject {
        // Bootstrap the api_token via getUserData before any other gw method.
        if (token == null && method != "deezer.getUserData") authorize()
        if (token == null) token = "null"
        val cookie = "arl=$arl" + (sid?.let { "; sid=$it" } ?: "")
        val data = post(
            "https://www.deezer.com/ajax/gw-light.php?method=$method&input=3&api_version=1.0&api_token=$token",
            body,
            mapOf("Cookie" to cookie),
        ) ?: throw IllegalStateException("empty gw-light response for $method")
        val out = JSONObject(data)

        if ((token == null || token == "null") && method == "deezer.getUserData") {
            val results = out.getJSONObject("results")
            token = results.getString("checkForm")
            sid = results.getString("SESSION_ID")
            runCatching {
                val options = results.getJSONObject("USER").getJSONObject("OPTIONS")
                licenseToken = options.getString("license_token")
                entitledQuality = qufrom(options)
                authorized = true
            }.onFailure { Log.w(TAG, "No license token — account may be logged out: $it") }
        }
        return out
    }

    /** Map account OPTIONS flags → highest entitled quality id. */
    private fun qufrom(options: JSONObject): Int = when {
        options.optBoolean("web_lossless") || options.optBoolean("mobile_lossless") -> QUALITY_FLAC
        options.optBoolean("web_hq") || options.optBoolean("mobile_hq") -> QUALITY_MP3_320
        else -> QUALITY_MP3_128
    }

    /** Public (unauthenticated) Deezer API call; blocking, may throw on network errors. */
    fun publicApi(path: String): JSONObject = callPublicApi(path)

    /**
     * Returns "code: message" when [json] is a Deezer API error object, else null.
     * Code 4 is the public API's rate limit ("Quota limit exceeded"), 800 is "no data".
     */
    fun apiError(json: JSONObject): String? =
        json.optJSONObject("error")?.let { "${it.optInt("code")}: ${it.optString("message")}" }

    fun isQuotaError(json: JSONObject): Boolean =
        json.optJSONObject("error")?.let {
            it.optInt("code") == 4 || it.optString("message").contains("quota", ignoreCase = true)
        } == true

    private fun callPublicApi(path: String): JSONObject {
        val url = URL("https://api.deezer.com/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept-Language", "en,*")
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        conn.connect()
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }

    private fun post(urlString: String, body: String?, headers: Map<String, String>): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en,*")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "POST $urlString failed: $e")
            null
        }
    }

    /** Deprecated legacy CDN url derivation (AES-ECB over md5(origin,quality,id,mv)). */
    private fun generateTrackUrl(tokens: TrackTokens, quality: Int): String? = try {
        val magic = 164
        val step1 = ByteArrayOutputStream().apply {
            write(tokens.md5origin.toByteArray()); write(magic)
            write(quality.toString().toByteArray()); write(magic)
            write(tokens.id.toByteArray()); write(magic)
            write(tokens.mediaVersion.toByteArray())
        }.toByteArray()
        val md5hex = MessageDigest.getInstance("MD5").digest(step1).toHexLower()
        val step2 = ByteArrayOutputStream().apply {
            write(md5hex.toByteArray()); write(magic)
            write(step1); write(magic)
            while (size() % 16 > 0) write(46)
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("jo6aey6haid2Teih".toByteArray(), "AES"))
        }
        val out = StringBuilder()
        for (i in 0 until step2.size / 16) {
            out.append(cipher.doFinal(step2.copyOfRange(i * 16, (i + 1) * 16)).toHexLower())
        }
        "https://e-cdns-proxy-${tokens.md5origin[0]}.dzcdn.net/mobile/1/$out"
    } catch (e: Exception) {
        Log.e(TAG, "legacy url gen failed: $e")
        null
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
