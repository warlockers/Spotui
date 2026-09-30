package com.music.spotui.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.utils.YTPlayerUtils
import com.music.spotui.deezer.DeezerAwareDataSourceFactory
import com.music.spotui.deezer.DeezerSource
import com.music.spotui.providers.DeezerAudioProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltViewModel
class AlternativeSearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var searchResults by mutableStateOf<List<SongItem>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var previewingVideoId by mutableStateOf<String?>(null)
        private set
    var isResolvingPreview by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // ── Deezer manual match ──
    var deezerResults by mutableStateOf<List<DeezerAudioProvider.SearchHit>>(emptyList())
        private set
    var isSearchingDeezer by mutableStateOf(false)
        private set
    var deezerQuery by mutableStateOf("")
        private set
    var deezerError by mutableStateOf<String?>(null)
        private set
    var previewingDeezerId by mutableStateOf<String?>(null)
        private set

    private var deezerSearchJob: Job? = null
    private var lastDeezerInitQuery: String = ""

    private var previewPlayer: ExoPlayer? = null
    private var searchJob: Job? = null
    private var resolveJob: Job? = null
    private var lastInitQuery: String = ""

    fun initQuery(title: String, artist: String) {
        val query = "$title $artist".trim()
        if (query == lastInitQuery) return
        lastInitQuery = query
        searchQuery = query
        searchResults = emptyList()
        error = null
        search(query)
    }

    fun updateQuery(query: String) {
        searchQuery = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            search(query)
        }
    }

    private fun search(query: String) {
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        isSearching = true
        error = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            }
            result.fold(
                onSuccess = { sr ->
                    searchResults = sr.items.filterIsInstance<SongItem>()
                },
                onFailure = { e ->
                    Log.w(TAG, "YouTube search failed", e)
                    error = "Search failed"
                    searchResults = emptyList()
                }
            )
            isSearching = false
        }
    }

    fun preview(song: SongItem) {
        if (previewingVideoId == song.id) {
            stopPreview()
            return
        }
        stopPreview()
        previewingVideoId = song.id
        isResolvingPreview = true
        com.music.spotui.di.SongPlayer.pause()
        resolveJob = viewModelScope.launch {
            val playback = withContext(Dispatchers.IO) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val quality = com.music.spotui.data.preferences.currentStreamingQuality(context)
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = song.id,
                    audioQuality = quality.audioQuality,
                    connectivityManager = cm,
                    skipValidation = true,
                )
            }
            playback.fold(
                onSuccess = { data ->
                    val player = getOrCreatePreviewPlayer()
                    withContext(Dispatchers.Main) {
                        player.setMediaItem(MediaItem.fromUri(data.streamUrl))
                        player.prepare()
                        player.play()
                    }
                    isResolvingPreview = false
                },
                onFailure = { e ->
                    Log.w(TAG, "Preview resolve failed for ${song.id}", e)
                    previewingVideoId = null
                    isResolvingPreview = false
                }
            )
        }
    }

    // ── Deezer search ────────────────────────────────────────────────────────

    fun initDeezerQuery(title: String, artist: String) {
        // Primary artist only: "Artist A, Artist B" as free text hurts Deezer's relevance.
        val query = "$title ${artist.substringBefore(',').trim()}".trim()
        if (query == lastDeezerInitQuery) return
        lastDeezerInitQuery = query
        deezerQuery = query
        deezerResults = emptyList()
        deezerError = null
        searchDeezer(query, debounce = false)
    }

    fun updateDeezerQuery(query: String) {
        deezerQuery = query
        searchDeezer(query, debounce = true)
    }

    private fun searchDeezer(query: String, debounce: Boolean) {
        deezerSearchJob?.cancel()
        if (query.isBlank()) {
            deezerResults = emptyList()
            deezerError = null
            isSearchingDeezer = false
            return
        }
        deezerSearchJob = viewModelScope.launch {
            if (debounce) delay(350)
            isSearchingDeezer = true
            deezerError = null
            val hits = withContext(Dispatchers.IO) {
                runCatching { DeezerAudioProvider.searchManual(query) }
                    .onFailure { Log.w(TAG, "Deezer search failed", it) }
                    .getOrNull()
            }
            if (hits == null) {
                deezerError = "Search failed"
                deezerResults = emptyList()
            } else {
                deezerResults = hits
            }
            isSearchingDeezer = false
        }
    }

    /**
     * Preview a Deezer hit. Uses the same full-stream resolution as real playback (low quality,
     * to start fast); without a usable Deezer login it falls back to Deezer's public 30s clip.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun previewDeezer(hit: DeezerAudioProvider.SearchHit) {
        if (previewingDeezerId == hit.trackId) {
            stopPreview()
            return
        }
        stopPreview()
        previewingDeezerId = hit.trackId
        isResolvingPreview = true
        deezerError = null
        com.music.spotui.di.SongPlayer.pause()
        resolveJob = viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val full = runCatching {
                    DeezerSource.resolveByTrackId(context, hit.trackId, maxFormat = 1)
                }.getOrNull()
                (full as? DeezerSource.Result.Success)?.uri ?: hit.previewUrl
            }
            if (uri == null) {
                previewingDeezerId = null
                isResolvingPreview = false
                deezerError = "Preview unavailable for this track"
                return@launch
            }
            val player = getOrCreatePreviewPlayer()
            withContext(Dispatchers.Main) {
                player.clearMediaItems()
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.w(TAG, "Deezer preview failed for ${hit.trackId}", error)
                        if (previewingDeezerId == hit.trackId) {
                            previewingDeezerId = null
                            isResolvingPreview = false
                            deezerError = "Preview failed"
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == androidx.media3.common.Player.STATE_ENDED &&
                            previewingDeezerId == hit.trackId
                        ) {
                            previewingDeezerId = null
                        }
                    }
                })
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        // deezer:// has no file extension; hint the container like the main player does.
                        if (uri.startsWith("deezer://")) setMimeType(androidx.media3.common.MimeTypes.AUDIO_MPEG)
                    }
                    .build()
                player.setMediaItem(item)
                player.prepare()
                player.play()
            }
            isResolvingPreview = false
        }
    }

    fun stopPreview() {
        resolveJob?.cancel()
        resolveJob = null
        previewPlayer?.let { p ->
            p.stop()
            p.release()
        }
        previewPlayer = null
        previewingVideoId = null
        previewingDeezerId = null
        isResolvingPreview = false
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun getOrCreatePreviewPlayer(): ExoPlayer {
        previewPlayer?.let { return it }
        val p = ExoPlayer.Builder(context)
            // Same routing as the main player: deezer:// (encrypted CDN) is decrypted on the fly,
            // everything else (YouTube URLs, Deezer clips) goes through the default HTTP stack.
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    DeezerAwareDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context))
                )
            )
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                false, // don't handle audio focus — this is transient preview
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        previewPlayer = p
        return p
    }

    override fun onCleared() {
        stopPreview()
    }

    companion object {
        private const val TAG = "AltSearchVM"
    }
}
