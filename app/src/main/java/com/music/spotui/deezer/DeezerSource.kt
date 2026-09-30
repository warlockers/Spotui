package com.music.spotui.deezer

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.music.spotui.data.preferences.getDeezerArl
import com.music.spotui.data.preferences.setDeezerTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Resolves a Spotify track to a playable Deezer stream. The bridge is the ISRC:
 * Spotify metadata carries it, and Deezer's public API maps `track/isrc:<ISRC>`
 * to a Deezer track id (the same path ReFreezer's Spotify importer uses). From
 * there [DeezerSession] mints an encrypted CDN url, which we wrap in a
 * `deezer://` URI for [DeezerDataSource] to fetch + decrypt.
 *
 * The chosen quality is whatever the signed-in account is entitled to (free →
 * MP3 128, Premium → MP3 320 / FLAC), with a graceful downgrade if the top
 * format isn't available for a given track.
 */
object DeezerSource {

    private const val TAG = "DeezerSource"

    sealed interface Result {
        /** [uri] is a `deezer://` URI; [mimeFlac] hints ExoPlayer for FLAC streams. */
        data class Success(val uri: String, val mimeFlac: Boolean, val qualityLabel: String) : Result
        data object NotLoggedIn : Result
        data object NotFound : Result
        data class Error(val message: String) : Result
    }

    /**
     * A resolved Deezer stream in raw form (used by the download path, which needs
     * the CDN url, encryption flag and track id to decrypt to a file itself).
     */
    data class Resolved(
        val url: String,
        val encrypted: Boolean,
        val trackId: String,
        val isFlac: Boolean,
        val qualityLabel: String,
    )

    /** Whether Deezer is configured (ARL present) — cheap, no network. */
    fun isConfigured(context: Context): Boolean = getDeezerArl(context) != null

    /**
     * Resolve a Spotify track to a raw Deezer stream (CDN url + crypto info), or
     * null if not logged in / no match / unavailable.
     *
     * @param spotifyId the Spotify track id (used to look up ISRC if not given)
     * @param isrc      the track's ISRC when already known (skips a Spotify call)
     * @param searchQuery "title artist" fallback if the ISRC has no Deezer match
     */
    suspend fun resolveRaw(
        context: Context,
        spotifyId: String?,
        isrc: String? = null,
        searchQuery: String? = null,
        maxFormat: Int? = null,
    ): Resolved? = withContext(Dispatchers.IO) {
        val arl = getDeezerArl(context) ?: return@withContext null
        DeezerSession.setArl(arl)
        DeezerSession.authorize()
        if (!DeezerSession.hasArl()) return@withContext null

        // ISRC → Deezer track id, falling back to a text search.
        val effectiveIsrc = isrc?.takeIf { it.isNotBlank() }
            ?: spotifyId?.let { runCatching { Spotify.track(it).getOrNull()?.isrc }.getOrNull() }
        val deezerId = effectiveIsrc?.let { DeezerSession.deezerIdForIsrc(it) }
            ?: searchQuery?.takeIf { it.isNotBlank() }?.let { DeezerSession.searchTrackId(it) }
            ?: return@withContext null

        rawForDeezerId(context, deezerId, effectiveIsrc, maxFormat)
    }

    /**
     * Resolve one specific Deezer track id (e.g. a user-pinned manual match) to a raw stream,
     * skipping any ISRC / text matching. Null if not logged in or the track can't be streamed.
     */
    suspend fun resolveRawByTrackId(
        context: Context,
        deezerTrackId: String,
        maxFormat: Int? = null,
    ): Resolved? = withContext(Dispatchers.IO) {
        val arl = getDeezerArl(context) ?: return@withContext null
        DeezerSession.setArl(arl)
        DeezerSession.authorize()
        if (!DeezerSession.hasArl()) return@withContext null
        rawForDeezerId(context, deezerTrackId.trim(), null, maxFormat)
    }

    /** Token lookup + quality negotiation shared by the matched and the pinned paths. */
    private fun rawForDeezerId(
        context: Context,
        deezerId: String,
        effectiveIsrc: String?,
        maxFormat: Int?,
    ): Resolved? {
        val tokens = DeezerSession.trackTokens(deezerId) ?: return null

        // Try the entitled quality, then degrade until one yields a url.
        val candidates = when (DeezerSession.entitledQuality) {
            DeezerSession.QUALITY_FLAC -> listOf(9, 3, 1)
            DeezerSession.QUALITY_MP3_320 -> listOf(3, 1)
            else -> listOf(1)
        }.let { list -> if (maxFormat == null) list else list.filter { it <= maxFormat }.ifEmpty { listOf(1) } }
        for (q in candidates) {
            val (url, encrypted) = DeezerSession.getTrackUrl(tokens, q)
            if (url != null) {
                // Persist tier for the settings screen (best-effort).
                runCatching { setDeezerTier(context, tierLabel(DeezerSession.entitledQuality)) }
                Log.d(TAG, "Deezer resolved id=${tokens.id} q=$q for isrc=$effectiveIsrc")
                return Resolved(
                    url = url,
                    encrypted = encrypted,
                    trackId = tokens.id,
                    isFlac = q == DeezerSession.QUALITY_FLAC,
                    qualityLabel = qualityLabel(q),
                )
            }
        }
        return null
    }

    /** Resolve a Spotify track to a playable `deezer://` URI. */
    suspend fun resolve(
        context: Context,
        spotifyId: String?,
        isrc: String? = null,
        searchQuery: String? = null,
        maxFormat: Int? = null,
    ): Result {
        if (getDeezerArl(context) == null) return Result.NotLoggedIn
        val raw = resolveRaw(context, spotifyId, isrc, searchQuery, maxFormat) ?: return Result.NotFound
        return raw.toSuccess()
    }

    /** Resolve a specific Deezer track id (a pinned manual match) to a playable `deezer://` URI. */
    suspend fun resolveByTrackId(
        context: Context,
        deezerTrackId: String,
        maxFormat: Int? = null,
    ): Result {
        if (getDeezerArl(context) == null) return Result.NotLoggedIn
        val raw = resolveRawByTrackId(context, deezerTrackId, maxFormat) ?: return Result.NotFound
        return raw.toSuccess()
    }

    private fun Resolved.toSuccess(): Result.Success {
        val uri = "deezer://stream" +
            "?u=${URLEncoder.encode(url, "UTF-8")}" +
            "&id=$trackId" +
            "&enc=${if (encrypted) 1 else 0}" +
            "&fmt=${if (isFlac) "flac" else "mp3"}"
        return Result.Success(uri = uri, mimeFlac = isFlac, qualityLabel = qualityLabel)
    }

    private fun qualityLabel(q: Int): String = when (q) {
        DeezerSession.QUALITY_FLAC -> "FLAC"
        DeezerSession.QUALITY_MP3_320 -> "MP3 320 kbps"
        else -> "MP3 128 kbps"
    }

    private fun tierLabel(q: Int): String = when (q) {
        DeezerSession.QUALITY_FLAC -> "Premium (FLAC)"
        DeezerSession.QUALITY_MP3_320 -> "Premium (MP3 320)"
        else -> "Free (MP3 128)"
    }
}
