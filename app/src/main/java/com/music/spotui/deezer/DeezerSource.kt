package com.music.spotui.deezer

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.music.spotui.data.preferences.getDeezerArl
import com.music.spotui.data.preferences.setDeezerTier
import com.music.spotui.providers.DeezerAudioProvider
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
 *
 * Matching is validated: every Deezer candidate (from the ISRC lookup or from a text search)
 * is scored against the wanted track (title / artist / album / duration / version markers,
 * same rules as the mirror resolver) and rejected below the threshold, so a wrong track is
 * never played just because Deezer returned it first. Up to a few candidates are tried
 * (region-blocked ones last, plus Deezer's own `alternative` / `FALLBACK` relinks) and every
 * failure is recorded as a reason that ends up in the resolution trace log.
 */
object DeezerSource {

    private const val TAG = "DeezerSource"

    /** Max candidates streamed per phase (ISRC phase / search phase). */
    private const val MAX_CANDIDATES_PER_PHASE = 3
    private const val SEARCH_LIMIT = 10

    sealed interface Result {
        /**
         * [uri] is a `deezer://` URI; [mimeFlac] hints ExoPlayer for FLAC streams;
         * [note] says which Deezer track was chosen and why (for the trace log).
         */
        data class Success(
            val uri: String,
            val mimeFlac: Boolean,
            val qualityLabel: String,
            val note: String = "",
        ) : Result
        data object NotLoggedIn : Result

        /** No stream could be produced. [reasons] is an ordered trace of why, see [describe]. */
        data class NotFound(val reasons: List<String> = emptyList()) : Result
        data class Error(val message: String) : Result
    }

    /** One-line, log-friendly explanation of a [Result] (null = the call itself threw). */
    fun describe(result: Result?): String = when (result) {
        null -> "error"
        is Result.Success -> "success"
        Result.NotLoggedIn -> "not logged in"
        is Result.NotFound ->
            if (result.reasons.isEmpty()) "NotFound" else "NotFound: ${result.reasons.joinToString(" | ")}"
        is Result.Error -> "Error: ${result.message}"
    }.take(700)

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
        val note: String = "",
    )

    /** Whether Deezer is configured (ARL present) — cheap, no network. */
    fun isConfigured(context: Context): Boolean = getDeezerArl(context) != null

    // ── Reasons / trace ──────────────────────────────────────────────────────

    private enum class Why {
        NOT_LOGGED_IN,
        NO_QUERY,
        ISRC_NO_MATCH,
        NO_SEARCH_RESULTS,
        MATCH_REJECTED,
        QUOTA_LIMIT,
        API_ERROR,
        NOT_READABLE,
        TOKEN_FAILED,
        STREAM_URL_FAILED,
    }

    private class Trace {
        val entries = mutableListOf<String>()
        fun add(why: Why, detail: String = "") {
            val d = detail.trim().take(180)
            entries += if (d.isEmpty()) why.name else "${why.name}($d)"
        }
    }

    private sealed interface Outcome {
        class Ok(val raw: Resolved) : Outcome
        class Fail(val reasons: List<String>) : Outcome
    }

    private class Candidate(
        val id: String,
        /** Null when not fetched yet (Deezer-provided relinks); validated lazily before streaming. */
        val info: DeezerAudioProvider.TrackInfo?,
        val score: Int?,
        val via: String,
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Resolve a Spotify track to a raw Deezer stream (CDN url + crypto info), or
     * null if not logged in / no acceptable match / unavailable.
     *
     * @param spotifyId the Spotify track id (used to look up ISRC if not given)
     * @param isrc      the track's ISRC when already known (skips a Spotify call)
     * @param searchQuery "title artist" fallback if the ISRC has no Deezer match
     * @param expect    what the track should look like (title/artists/album/duration). When given,
     *                  every Deezer candidate is validated against it; when null the first
     *                  ISRC/search hit is used unvalidated (legacy behaviour).
     */
    suspend fun resolveRaw(
        context: Context,
        spotifyId: String?,
        isrc: String? = null,
        searchQuery: String? = null,
        maxFormat: Int? = null,
        expect: DeezerAudioProvider.Query? = null,
    ): Resolved? =
        (resolveOutcome(context, spotifyId, isrc, searchQuery, maxFormat, expect) as? Outcome.Ok)?.raw

    /** Resolve a Spotify track to a playable `deezer://` URI. */
    suspend fun resolve(
        context: Context,
        spotifyId: String?,
        isrc: String? = null,
        searchQuery: String? = null,
        maxFormat: Int? = null,
        expect: DeezerAudioProvider.Query? = null,
    ): Result {
        if (getDeezerArl(context) == null) return Result.NotLoggedIn
        return when (val outcome = resolveOutcome(context, spotifyId, isrc, searchQuery, maxFormat, expect)) {
            is Outcome.Ok -> outcome.raw.toSuccess()
            is Outcome.Fail -> Result.NotFound(outcome.reasons)
        }
    }

    /**
     * Resolve one specific Deezer track id (e.g. a user-pinned manual match) to a raw stream,
     * skipping any ISRC / text matching. Null if not logged in or the track can't be streamed.
     */
    suspend fun resolveRawByTrackId(
        context: Context,
        deezerTrackId: String,
        maxFormat: Int? = null,
    ): Resolved? = (resolvePinnedOutcome(context, deezerTrackId, maxFormat) as? Outcome.Ok)?.raw

    /** Resolve a specific Deezer track id (a pinned manual match) to a playable `deezer://` URI. */
    suspend fun resolveByTrackId(
        context: Context,
        deezerTrackId: String,
        maxFormat: Int? = null,
    ): Result {
        if (getDeezerArl(context) == null) return Result.NotLoggedIn
        return when (val outcome = resolvePinnedOutcome(context, deezerTrackId, maxFormat)) {
            is Outcome.Ok -> outcome.raw.toSuccess()
            is Outcome.Fail -> Result.NotFound(outcome.reasons)
        }
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    private suspend fun resolveOutcome(
        context: Context,
        spotifyId: String?,
        isrc: String?,
        searchQuery: String?,
        maxFormat: Int?,
        expect: DeezerAudioProvider.Query?,
    ): Outcome = withContext(Dispatchers.IO) {
        val trace = Trace()
        if (!authorizeSession(context)) {
            trace.add(Why.NOT_LOGGED_IN)
            return@withContext Outcome.Fail(trace.entries)
        }

        val effectiveIsrc = isrc?.takeIf { it.isNotBlank() }
            ?: spotifyId?.let { runCatching { Spotify.track(it).getOrNull()?.isrc }.getOrNull() }
        // Make sure the ISRC we found participates in scoring (an ISRC hit is authoritative).
        val wanted = expect?.let { if (effectiveIsrc != null) it.copy(isrc = effectiveIsrc) else it }

        if (effectiveIsrc == null && wanted?.title.isNullOrBlank() && searchQuery.isNullOrBlank()) {
            trace.add(Why.NO_QUERY, "no ISRC, title or search text")
            return@withContext Outcome.Fail(trace.entries)
        }

        val tried = mutableSetOf<String>()
        fun tryAll(candidates: List<Candidate>): Resolved? {
            for (c in candidates.take(MAX_CANDIDATES_PER_PHASE)) {
                if (!tried.add(c.id)) continue
                streamCandidate(context, c, wanted, maxFormat, trace, depth = 0)?.let { return it }
            }
            return null
        }

        // Phase 1: exact identity via ISRC (validated by duration etc.).
        if (effectiveIsrc != null) {
            tryAll(candidatesFromIsrc(effectiveIsrc, wanted, trace))?.let { return@withContext Outcome.Ok(it) }
        }

        // Phase 2: scored text search — also reached when the ISRC track is blocked in this
        // region, because another release of the same recording may be available.
        tryAll(candidatesFromSearch(wanted, searchQuery, trace))?.let { return@withContext Outcome.Ok(it) }

        Outcome.Fail(trace.entries)
    }

    private suspend fun resolvePinnedOutcome(
        context: Context,
        deezerTrackId: String,
        maxFormat: Int?,
    ): Outcome = withContext(Dispatchers.IO) {
        val trace = Trace()
        if (!authorizeSession(context)) {
            trace.add(Why.NOT_LOGGED_IN)
            return@withContext Outcome.Fail(trace.entries)
        }
        // The user chose this exact track: no scoring, but Deezer's own relink is still allowed.
        val raw = streamCandidate(
            context,
            Candidate(deezerTrackId.trim(), info = null, score = null, via = "pinned"),
            wanted = null,
            maxFormat = maxFormat,
            trace = trace,
            depth = 0,
        )
        if (raw != null) Outcome.Ok(raw) else Outcome.Fail(trace.entries)
    }

    private fun authorizeSession(context: Context): Boolean {
        val arl = getDeezerArl(context) ?: return false
        DeezerSession.setArl(arl)
        DeezerSession.authorize()
        return DeezerSession.hasArl()
    }

    // ── Candidate discovery ──────────────────────────────────────────────────

    /** Accepts [info] as a candidate if it matches [wanted] (or unconditionally when there is no expectation). */
    private fun consider(
        info: DeezerAudioProvider.TrackInfo,
        wanted: DeezerAudioProvider.Query?,
        via: String,
    ): Candidate? {
        val score = wanted?.let { DeezerAudioProvider.scoreMatch(it, info) }
        if (score != null && !DeezerAudioProvider.isAcceptableScore(score)) return null
        return Candidate(info.trackId, info, score, via)
    }

    /** Readable (or unknown) first, then best score; region-blocked ones go last. */
    private fun List<Candidate>.prioritised(): List<Candidate> =
        sortedWith(
            compareBy<Candidate> { if (it.info?.readable == false) 1 else 0 }
                .thenByDescending { it.score ?: 0 }
        )

    private fun candidatesFromIsrc(
        isrc: String,
        wanted: DeezerAudioProvider.Query?,
        trace: Trace,
    ): List<Candidate> {
        val json = runCatching { DeezerSession.publicApi("track/isrc:${isrc.trim().uppercase()}") }
            .getOrElse {
                trace.add(Why.API_ERROR, "isrc lookup: ${it.javaClass.simpleName}: ${it.message}")
                return emptyList()
            }
        if (DeezerSession.isQuotaError(json)) {
            trace.add(Why.QUOTA_LIMIT, "isrc lookup")
            return emptyList()
        }
        DeezerSession.apiError(json)?.let { err ->
            trace.add(Why.ISRC_NO_MATCH, "$isrc: $err")
            return emptyList()
        }
        val info = DeezerAudioProvider.parseTrackInfo(json)
        if (info == null) {
            trace.add(Why.ISRC_NO_MATCH, isrc)
            return emptyList()
        }
        val candidate = consider(info, wanted, "isrc")
        if (candidate == null) {
            trace.add(Why.MATCH_REJECTED, "isrc $isrc -> ${info.brief()} (${rejectionDetail(wanted, info)})")
            return emptyList()
        }
        return buildList {
            add(candidate)
            // Region-blocked here? Deezer names its relinked replacement; it is validated before use.
            if (info.readable == false) {
                info.alternativeId?.takeIf { it != info.trackId }?.let {
                    add(Candidate(it, info = null, score = candidate.score, via = "isrc/alternative"))
                }
            }
        }.prioritised()
    }

    private fun candidatesFromSearch(
        wanted: DeezerAudioProvider.Query?,
        searchQuery: String?,
        trace: Trace,
    ): List<Candidate> {
        val terms = searchTerms(wanted, searchQuery)
        if (terms.isEmpty()) {
            trace.add(Why.NO_QUERY, "nothing to search for")
            return emptyList()
        }
        for (term in terms) {
            val json = runCatching {
                DeezerSession.publicApi("search/track?q=${URLEncoder.encode(term, "UTF-8")}&limit=$SEARCH_LIMIT")
            }.getOrElse {
                trace.add(Why.API_ERROR, "search '${term.take(60)}': ${it.javaClass.simpleName}")
                continue
            }
            if (DeezerSession.isQuotaError(json)) {
                trace.add(Why.QUOTA_LIMIT, "search")
                return emptyList() // further calls would just be rate limited too
            }
            DeezerSession.apiError(json)?.let { err ->
                trace.add(Why.API_ERROR, "search '${term.take(60)}': $err")
                continue
            }
            val data = json.optJSONArray("data")
            val infos = buildList {
                if (data != null) {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.let(DeezerAudioProvider::parseTrackInfo)?.let(::add)
                    }
                }
            }
            if (infos.isEmpty()) {
                trace.add(Why.NO_SEARCH_RESULTS, "'${term.take(60)}'")
                continue
            }
            if (wanted == null) {
                // No expectation to validate against: legacy behaviour = first hit.
                return listOfNotNull(consider(infos.first(), null, "search"))
            }
            val accepted = infos.mapNotNull { consider(it, wanted, "search") }
            if (accepted.isNotEmpty()) return accepted.prioritised()
            val best = infos.maxByOrNull { DeezerAudioProvider.scoreMatch(wanted, it) }!!
            trace.add(
                Why.MATCH_REJECTED,
                "search '${term.take(60)}': best ${best.brief()} (${rejectionDetail(wanted, best)})",
            )
        }
        return emptyList()
    }

    private fun searchTerms(wanted: DeezerAudioProvider.Query?, searchQuery: String?): List<String> =
        buildList {
            if (wanted != null && wanted.title.isNotBlank()) {
                val title = DeezerAudioProvider.cleanTitleForSearch(wanted.title)
                val artist = wanted.artists.firstOrNull { it.isNotBlank() }.orEmpty()
                if (title.isNotBlank() && artist.isNotBlank()) {
                    // Deezer advanced search: quoted fields are far more precise than free text.
                    add("artist:\"${artist.replace('"', ' ')}\" track:\"${title.replace('"', ' ')}\"")
                }
                add("$title $artist".trim())
            }
            searchQuery?.takeIf { it.isNotBlank() }?.let(::add)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    // ── Streaming ────────────────────────────────────────────────────────────

    /**
     * Turns one candidate into a stream. Validates Deezer-provided relinks first (they arrive
     * without metadata), negotiates quality, and — if the track itself is blocked — follows
     * Deezer's own `FALLBACK` id once. Every failure is recorded in [trace].
     */
    private fun streamCandidate(
        context: Context,
        candidate: Candidate,
        wanted: DeezerAudioProvider.Query?,
        maxFormat: Int?,
        trace: Trace,
        depth: Int,
    ): Resolved? {
        var info = candidate.info
        var score = candidate.score
        if (info == null && wanted != null) {
            val json = runCatching { DeezerSession.publicApi("track/${candidate.id}") }.getOrNull()
            info = json?.takeIf { DeezerSession.apiError(it) == null }?.let(DeezerAudioProvider::parseTrackInfo)
            if (info == null) {
                trace.add(Why.API_ERROR, "could not verify ${candidate.via} id=${candidate.id}; skipped")
                return null
            }
            score = DeezerAudioProvider.scoreMatch(wanted, info)
            if (!DeezerAudioProvider.isAcceptableScore(score)) {
                trace.add(
                    Why.MATCH_REJECTED,
                    "${candidate.via} id=${candidate.id} ${info.brief()} (${rejectionDetail(wanted, info)})",
                )
                return null
            }
        }

        val (tokens, tokenError) = DeezerSession.trackTokensOrError(candidate.id)
        if (tokens == null) {
            trace.add(Why.TOKEN_FAILED, "id=${candidate.id} via ${candidate.via}: $tokenError")
            return null
        }

        // Try the entitled quality, then degrade until one yields a url.
        val qualities = when (DeezerSession.entitledQuality) {
            DeezerSession.QUALITY_FLAC -> listOf(9, 3, 1)
            DeezerSession.QUALITY_MP3_320 -> listOf(3, 1)
            else -> listOf(1)
        }.let { list -> if (maxFormat == null) list else list.filter { it <= maxFormat }.ifEmpty { listOf(1) } }

        var lastError: String? = null
        for (q in qualities) {
            val minted = DeezerSession.mintTrackUrl(tokens, q)
            if (minted.url != null) {
                // Persist tier for the settings screen (best-effort).
                runCatching { setDeezerTier(context, tierLabel(DeezerSession.entitledQuality)) }
                val note = buildString {
                    append("matched id=${tokens.id} via ${candidate.via}")
                    score?.let { append(" score=$it") }
                    info?.let { append(" ${it.brief()}") }
                }
                Log.d(TAG, "Deezer resolved: $note (q=$q)")
                return Resolved(
                    url = minted.url,
                    encrypted = minted.encrypted,
                    trackId = tokens.id,
                    isFlac = q == DeezerSession.QUALITY_FLAC,
                    qualityLabel = qualityLabel(q),
                    note = note,
                )
            }
            lastError = minted.error
        }

        val blocked = info?.readable == false
        trace.add(
            if (blocked) Why.NOT_READABLE else Why.STREAM_URL_FAILED,
            "id=${candidate.id} via ${candidate.via}: $lastError",
        )
        if (depth == 0) {
            tokens.fallbackId?.let { fallbackId ->
                return streamCandidate(
                    context,
                    Candidate(fallbackId, info = null, score = score, via = "${candidate.via}/FALLBACK"),
                    wanted, maxFormat, trace, depth = 1,
                )
            }
        }
        return null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun DeezerAudioProvider.TrackInfo.brief(): String {
        val secs = durationMs?.let { " ${it / 60_000}:${"%02d".format(it / 1000 % 60)}" }.orEmpty()
        return "'$title' - ${artists.firstOrNull().orEmpty()}$secs"
    }

    private fun rejectionDetail(
        wanted: DeezerAudioProvider.Query?,
        info: DeezerAudioProvider.TrackInfo,
    ): String {
        if (wanted == null) return "no expectation"
        val score = DeezerAudioProvider.scoreMatch(wanted, info)
        val wantedSecs = wanted.durationMs?.takeIf { it > 0 }?.let { " want ${it / 60_000}:${"%02d".format(it / 1000 % 60)}" }.orEmpty()
        val shown = if (score <= -1_000_000) "rejected" else "score=$score"
        return "$shown$wantedSecs"
    }

    private fun Resolved.toSuccess(): Result.Success {
        val uri = "deezer://stream" +
            "?u=${URLEncoder.encode(url, "UTF-8")}" +
            "&id=$trackId" +
            "&enc=${if (encrypted) 1 else 0}" +
            "&fmt=${if (isFlac) "flac" else "mp3"}"
        return Result.Success(uri = uri, mimeFlac = isFlac, qualityLabel = qualityLabel, note = note)
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
