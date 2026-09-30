package com.music.spotui.data.preferences

import android.content.Context
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject

data class AlternativeStream(
    val type: String,
    val value: String,
    val label: String = "",
) {
    val isYouTube: Boolean get() = type == TYPE_YOUTUBE
    val isLocal: Boolean get() = type == TYPE_LOCAL
    val isDeezer: Boolean get() = type == TYPE_DEEZER

    companion object {
        const val TYPE_YOUTUBE = "youtube"
        const val TYPE_LOCAL = "local"

        /** [value] is the Deezer track id; [label] is a human readable "Title - Artist". */
        const val TYPE_DEEZER = "deezer"
    }
}

/**
 * Manually pinned track matches ("Alternative stream").
 *
 * This is user CONFIGURATION, not a cache: it lives in its own preferences file, is never
 * touched by any cache clearing / invalidation, and YouTube + Deezer pins are part of the
 * in-app backup (see [exportPortableAlternativeStreams] / [importPortableAlternativeStreams]).
 * The only things that remove an entry are the explicit "Clear alternative stream" action
 * and [clearAllAlternativeStreams].
 */
private const val PREF = "AlternativeStreams"

fun alternativeStreamKey(song: SongsModel): String =
    song.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:$it" } ?: "song:${song.id}"

fun alternativeStreamKeyForSpotifyId(spotifyTrackId: String): String =
    "spotify:$spotifyTrackId"

private fun AlternativeStream.toJson(): String = JSONObject().apply {
    put("type", type)
    put("value", value)
    put("label", label)
}.toString()

private fun parseAlternativeStream(raw: String): AlternativeStream? = runCatching {
    val obj = JSONObject(raw)
    AlternativeStream(
        type = obj.optString("type"),
        value = obj.optString("value"),
        label = obj.optString("label"),
    ).takeIf { it.value.isNotBlank() && (it.isYouTube || it.isLocal || it.isDeezer) }
}.getOrNull()

fun getAlternativeStream(context: Context, key: String): AlternativeStream? =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(key, null)
        ?.let(::parseAlternativeStream)

fun setYouTubeAlternativeStream(context: Context, key: String, videoId: String) {
    val stream = AlternativeStream(
        type = AlternativeStream.TYPE_YOUTUBE,
        value = videoId,
        label = "YouTube video",
    )
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .putString(key, stream.toJson())
        .apply()
}

fun setDeezerAlternativeStream(context: Context, key: String, trackId: String, label: String = "") {
    val stream = AlternativeStream(
        type = AlternativeStream.TYPE_DEEZER,
        value = trackId,
        label = label.ifBlank { "Deezer track $trackId" },
    )
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .putString(key, stream.toJson())
        .apply()
}

fun setLocalAlternativeStream(context: Context, key: String, uri: Uri, label: String = "") {
    val stream = AlternativeStream(
        type = AlternativeStream.TYPE_LOCAL,
        value = uri.toString(),
        label = label.ifBlank { uri.lastPathSegment.orEmpty() },
    )
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .putString(key, stream.toJson())
        .apply()
}

fun clearAlternativeStream(context: Context, key: String) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .remove(key)
        .apply()
}

fun clearAllAlternativeStreams(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

// ── Backup / restore ─────────────────────────────────────────────────────────
// Only YouTube and Deezer pins are portable. Local-file pins hold a content:// URI whose
// permission grant does not survive a reinstall or another device, so they are skipped.

/** All portable pins as `{ key: { type, value, label? } }`, ready to embed in a backup. */
fun exportPortableAlternativeStreams(context: Context): JSONObject {
    val out = JSONObject()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).all.forEach { (key, raw) ->
        val stream = (raw as? String)?.let(::parseAlternativeStream) ?: return@forEach
        if (stream.isLocal) return@forEach
        out.put(key, JSONObject().apply {
            put("type", stream.type)
            put("value", stream.value)
            if (stream.label.isNotBlank()) put("label", stream.label)
        })
    }
    return out
}

/** Merges pins from [json] (as produced by [exportPortableAlternativeStreams]). Returns how many were restored. */
fun importPortableAlternativeStreams(context: Context, json: JSONObject): Int {
    val editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
    var restored = 0
    json.keys().forEach { key ->
        val obj = json.optJSONObject(key) ?: return@forEach
        val stream = parseAlternativeStream(obj.toString()) ?: return@forEach
        if (stream.isLocal || key.isBlank()) return@forEach
        editor.putString(key, stream.toJson())
        restored++
    }
    editor.apply()
    return restored
}
