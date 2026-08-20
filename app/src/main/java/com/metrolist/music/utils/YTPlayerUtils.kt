package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_NO_SDK
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import com.music.spotui.utils.StreamLogger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"
    /** Max seconds to wait for signature-timestamp resolution before giving up. */
    private const val SIG_FUTURE_TIMEOUT_SEC = 10L
    /** Max seconds to wait for PoToken generation before giving up. */
    private const val POT_FUTURE_TIMEOUT_SEC = 14L

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        IOS,
        IPADOS,
        MOBILE,
        ANDROID_NO_SDK,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        ANDROID_VR_NO_AUTH,
        WEB,
        WEB_CREATOR
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        skipValidation: Boolean = false,
    ): Result<PlaybackData> = runCatching {
        StreamLogger.logStream(TAG, "=== PLAYER RESPONSE FOR PLAYBACK ===")
        StreamLogger.logStream(TAG, "videoId: $videoId")
        StreamLogger.logStream(TAG, "audioQuality: $audioQuality")
        StreamLogger.logStream(TAG, "metered network: ${connectivityManager.isActiveNetworkMetered}")
        
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens

        val sigFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            getSignatureTimestampOrNull(videoId)
        }
        val potFuture: java.util.concurrent.CompletableFuture<PoTokenResult?>? =
            if (mainClientNeedsPoToken && sessionId != null) {
                java.util.concurrent.CompletableFuture.supplyAsync {
                    Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
                    try {
                        poTokenGenerator.getWebClientPoToken(videoId, sessionId).also {
                            if (it != null) Timber.tag(logTag).d("PoToken generated successfully")
                        }
                    } catch (e: Exception) {
                        StreamLogger.logStreamError(logTag, "PoToken generation failed", e)
                        Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
                        null
                    }
                }
            } else null

        val signatureTimestamp = try {
            sigFuture.get(SIG_FUTURE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            StreamLogger.logTimeout(logTag, "Signature timestamp", SIG_FUTURE_TIMEOUT_SEC * 1000, e.message ?: "Unknown")
            Timber.tag(logTag).w("Signature timestamp timed out or failed: ${e.message}")
            SignatureTimestampResult(null, isAgeRestricted = false)
        }
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")
        var poToken: PoTokenResult? = try {
            potFuture?.get(POT_FUTURE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            StreamLogger.logTimeout(logTag, "PoToken generation", POT_FUTURE_TIMEOUT_SEC * 1000, e.message ?: "Unknown")
            Timber.tag(logTag).w("PoToken timed out or failed: ${e.message}")
            null
        }

        val skipMainClient = mainClientNeedsPoToken && poToken == null
        if (skipMainClient) {
            StreamLogger.logStreamWarning(TAG, "PoToken unavailable — skipping MAIN_CLIENT and using fallback chain")
            Timber.tag(TAG).w("PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
        }

        var mainPlayerResponse: PlayerResponse? = if (skipMainClient) null else {
            Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrNull()
        }

        if (mainPlayerResponse != null && (isUploadedTrack || playlistId?.contains("MLPT") == true)) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        val mainStatus = mainPlayerResponse?.playabilityStatus?.status
        val mainReason = mainPlayerResponse?.playabilityStatus?.reason.orEmpty()

        val isBotDetection = mainReason.contains("bot", ignoreCase = true) ||
                mainReason.contains("confirm you", ignoreCase = true) ||
                mainReason.contains("Sign in to confirm", ignoreCase = true) ||
                mainReason.contains("Log in to confirm", ignoreCase = true)

        if (isBotDetection) {
            StreamLogger.logStreamWarning(TAG, "Bot detection triggered on MAIN_CLIENT")
            Timber.tag(TAG).w("Bot detection triggered on MAIN_CLIENT (reason: $mainReason). Falling back to non-PoToken client chain.")
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { YouTube.visitorData = YouTube.visitorData().getOrNull() ?: YouTube.visitorData }
            }
        }

        val isAgeRestrictedFromResponse = !isBotDetection && (
            mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED") ||
            (mainStatus == "LOGIN_REQUIRED" && (mainReason.contains("age", ignoreCase = true) || mainReason.contains("verify", ignoreCase = true)))
        )
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        val isAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is age-restricted (status: $mainStatus), will try fallback clients")
            Timber.tag(TAG).i("Age-restricted content detected: videoId=$videoId, status=$mainStatus")
        }

        val isPrivateTrack = mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        val startIndex = when {
            isPrivateTrack -> 1
            isAgeRestricted -> 0
            skipMainClient -> 0
            mainPlayerResponse == null || mainPlayerResponse.playabilityStatus.status != "OK" -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client: YouTubeClient
            if (clientIndex == -1) {
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                StreamLogger.logStream(logTag, "Trying stream from MAIN_CLIENT: ${client.clientName}")
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                StreamLogger.logStream(logTag, "Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            val returnedVideoId = streamPlayerResponse?.videoDetails?.videoId
            if (returnedVideoId != null && returnedVideoId != videoId) {
                StreamLogger.logStreamWarning(TAG, "Client returned WRONG video: $returnedVideoId != $videoId")
                Timber.tag(TAG).w(
                    "Client ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName} " +
                        "returned WRONG video: $returnedVideoId != $videoId — skipping",
                )
                continue
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                StreamLogger.logStream(logTag, "Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                val responseToUse = if (wasOriginallyAgeRestricted) {
                    Timber.tag(logTag).d("Skipping NewPipe for age-restricted content")
                    streamPlayerResponse
                } else {
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                format = findFormat(
                    responseToUse,
                    audioQuality,
                    connectivityManager,
                    videoId
                )

                if (format == null) {
                    StreamLogger.logStreamWarning(logTag, "[$videoId] No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                StreamLogger.logResolution(logTag, videoId, format.mimeType, format.bitrate)
                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                if (streamUrl == null) {
                    StreamLogger.logStreamWarning(logTag, "[$videoId] Stream URL not found for format")
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                val hasNParam = streamUrl.contains(Regex("[?&]n="))
                val needsNTransform = hasNParam || currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER") ||
                    isPrivatelyOwnedTrack

                if (needsNTransform) {
                    try {
                        StreamLogger.logStream(TAG, "[$videoId] Applying n-transform...")
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")

                        val originalUrl = streamUrl
                        streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)
                        if (hasNParam && streamUrl == originalUrl) {
                            Timber.tag(TAG).d("CipherDeobfuscator left n-param unchanged, trying EjsNTransformSolver fallback...")
                            streamUrl = EjsNTransformSolver.transformNParamInUrl(originalUrl)
                        }

                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                        }
                    } catch (e: Exception) {
                        StreamLogger.logStreamError(TAG, "[$videoId] N-transform or pot append failed", e)
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                    }
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    StreamLogger.logStreamWarning(logTag, "[$videoId] Stream expiration time not found")
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                StreamLogger.logBuffer(logTag, videoId, "EXPIRES_IN", "${streamExpiresInSeconds}s")
                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    if (isPrivatelyOwned) {
                        StreamLogger.logBuffer(logTag, videoId, "SKIP_VALIDATION", "private track")
                        Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                    } else {
                        StreamLogger.logBuffer(logTag, videoId, "SKIP_VALIDATION", "last fallback")
                        Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    }
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (skipValidation || validateStatus(streamUrl, videoId)) {
                    StreamLogger.logBuffer(logTag, videoId, "VALIDATED", currentClient.clientName)
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    StreamLogger.logStreamWarning(logTag, "[$videoId] Stream validation failed for client: ${currentClient.clientName}")
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                }
            } else {
                StreamLogger.logStreamWarning(logTag, "[$videoId] Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}")
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            StreamLogger.logStreamError(TAG, "[$videoId] All clients failed")
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            StreamLogger.logStreamError(TAG, "[$videoId] Playability not OK: $errorReason")
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            StreamLogger.logStreamError(TAG, "[$videoId] Missing stream expire time")
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            StreamLogger.logStreamError(TAG, "[$videoId] Could not find format")
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            StreamLogger.logStreamError(TAG, "[$videoId] Could not find stream url")
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        StreamLogger.logStream(logTag, "[$videoId] ✓ SUCCESS: format=${format.mimeType}, bitrate=${format.bitrate / 1000}kbps")
        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        PlaybackData(
            streamPlayerResponse?.playerConfig?.audioConfig ?: mainPlayerResponse?.playerConfig?.audioConfig,
            streamPlayerResponse?.videoDetails ?: mainPlayerResponse?.videoDetails,
            streamPlayerResponse?.playbackTracking ?: mainPlayerResponse?.playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        StreamLogger.logStreamError(TAG, "EXCEPTION during playback resolution", e)
        println("[PLAYBACK_DEBUG] EXCEPTION during playback: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        videoId: String = "",
    ): PlayerResponse.StreamingData.Format? {
        StreamLogger.logStream(logTag, "[$videoId] Finding format: quality=$audioQuality, metered=${connectivityManager.isActiveNetworkMetered}")
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
            }

        if (format != null) {
            StreamLogger.logStream(logTag, "[$videoId] Selected: ${format.mimeType} @ ${format.bitrate / 1000}kbps")
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            StreamLogger.logStreamWarning(logTag, "[$videoId] No suitable audio format found in ${playerResponse.streamingData?.adaptiveFormats?.size ?: 0} formats")
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }

    /**
     * Checks if the stream url returns a successful status.
     */
    internal fun validateStatus(url: String, videoId: String = ""): Boolean {
        StreamLogger.logStream(logTag, "[$videoId] Validating stream URL...")
        Timber.tag(logTag).d("Validating stream URL status via GET Range bytes=0-0")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .get()
                .addHeader("Range", "bytes=0-0")
                .url(url)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = (response.isSuccessful || code == 206) && code != 403 && code != 404 && code != 410
            StreamLogger.logValidation(logTag, url, code, accepted)
            Timber.tag(logTag).d("Stream URL validation: code=$code accepted=$accepted")
            return accepted
        } catch (e: java.io.IOException) {
            StreamLogger.logStreamWarning(logTag, "[$videoId] Stream URL probe timed out (accepting optimistically)")
            Timber.tag(logTag).w(e, "Stream URL probe failed (IO); accepting optimistically")
            return true
        } catch (e: Exception) {
            StreamLogger.logStreamError(logTag, "[$videoId] Stream URL validation failed", e)
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }

    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    StreamLogger.logStreamError(logTag, "Failed to get signature timestamp for $videoId", error)
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        if (!format.url.isNullOrEmpty()) {
            StreamLogger.logStream(logTag, "[$videoId] Using URL from format directly")
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                StreamLogger.logStream(logTag, "[$videoId] URL via custom cipher")
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            StreamLogger.logStream(logTag, "[$videoId] URL via NewPipe")
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                StreamLogger.logStream(logTag, "[$videoId] URL from StreamInfo (exact match)")
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                StreamLogger.logStream(logTag, "[$videoId] URL from StreamInfo (audio match)")
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        StreamLogger.logStreamError(logTag, "[$videoId] Failed to get stream URL")
        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }

    fun resetSession(context: Context) {
        poTokenGenerator.reset()
        com.music.spotui.data.preferences.clearAllCachedStreams(context)
        com.music.spotui.data.preferences.clearAllResolvedVideos(context)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { YouTube.visitorData = YouTube.visitorData().getOrNull() }
        }
    }
}
