package com.music.spotui.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Centralized logging system for audio streaming diagnostics.
 * Logs can be viewed in-app and exported to a file.
 */
object StreamLogger {
    private const val TAG = "StreamLogger"
    private const val MAX_LOGS = 1000
    private const val LOG_FILE_NAME = "stream_logs.txt"
    
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var loggingEnabled = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val exception: String? = null
    ) {
        fun format(): String = "[${dateFormat.format(Date(timestamp))}] $level/$tag: $message${if (exception != null) "\n$exception" else ""}"
    }
    
    /**
     * Initialize logging system and read saved preference
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("stream_logging", Context.MODE_PRIVATE)
        loggingEnabled = prefs.getBoolean("logging_enabled", false)
        if (loggingEnabled) {
            Timber.tag(TAG).d("Logging system initialized (enabled)")
        }
    }
    
    /**
     * Toggle logging on/off and persist preference
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        loggingEnabled = enabled
        context.getSharedPreferences("stream_logging", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("logging_enabled", enabled)
            .apply()
        Timber.tag(TAG).i("Logging ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun isEnabled(): Boolean = loggingEnabled
    
    /**
     * Log audio stream event (normal level)
     */
    fun logStream(tag: String, message: String) {
        if (!loggingEnabled) return
        addLog("INFO", tag, message)
        Timber.tag(tag).d(message)
    }
    
    /**
     * Log streaming warning
     */
    fun logStreamWarning(tag: String, message: String) {
        if (!loggingEnabled) return
        addLog("WARN", tag, message)
        Timber.tag(tag).w(message)
    }
    
    /**
     * Log streaming error with optional exception
     */
    fun logStreamError(tag: String, message: String, exception: Throwable? = null) {
        if (!loggingEnabled) return
        val exceptionStr = exception?.let {
            "${it::class.simpleName}: ${it.message}\n${it.stackTraceToString().take(500)}"
        }
        addLog("ERROR", tag, message, exceptionStr)
        if (exception != null) {
            Timber.tag(tag).e(exception, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
    
    /**
     * Log buffering/loading event
     */
    fun logBuffer(tag: String, videoId: String, action: String, details: String = "") {
        if (!loggingEnabled) return
        val msg = "[$videoId] BUFFER: $action${if (details.isNotBlank()) " - $details" else ""}"
        addLog("BUFFER", tag, msg)
    }
    
    /**
     * Log CDN/network validation
     */
    fun logValidation(tag: String, url: String, statusCode: Int, accepted: Boolean) {
        if (!loggingEnabled) return
        val msg = "Validation: ${url.take(80)}... → HTTP $statusCode (${if (accepted) "✓ OK" else "✗ FAILED"})"
        addLog("NETWORK", tag, msg)
    }
    
    /**
     * Log timeout event
     */
    fun logTimeout(tag: String, operation: String, timeoutMs: Long, message: String = "") {
        if (!loggingEnabled) return
        val msg = "TIMEOUT: $operation (${timeoutMs}ms)${if (message.isNotBlank()) " - $message" else ""}"
        addLog("TIMEOUT", tag, msg)
    }
    
    /**
     * Log stream URL resolution
     */
    fun logResolution(tag: String, videoId: String, format: String, bitrate: Int) {
        if (!loggingEnabled) return
        val msg = "[$videoId] RESOLVED: $format @ ${bitrate / 1000}kbps"
        addLog("RESOLVE", tag, msg)
    }
    
    /**
     * Log stream playback issue
     */
    fun logPlaybackError(tag: String, videoId: String, positionMs: Long, reason: String) {
        if (!loggingEnabled) return
        val msg = "[$videoId] PLAYBACK_ERROR at ${positionMs / 1000}s: $reason"
        addLog("PLAYBACK", tag, msg)
    }
    
    /**
     * Get all logs as list
     */
    fun getLogs(): List<LogEntry> = logQueue.toList()
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        logQueue.clear()
        Timber.tag(TAG).i("Logs cleared")
    }
    
    /**
     * Export logs to file
     */
    fun exportLogs(context: Context): File? = try {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        logsDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val logFile = File(logsDir, "stream_logs_$timestamp.txt")
        
        logFile.writeText(
            logQueue.joinToString("\n") { it.format() }
        )
        Timber.tag(TAG).i("Logs exported to ${logFile.absolutePath}")
        logFile
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to export logs")
        null
    }
    
    private fun addLog(level: String, tag: String, message: String, exception: String? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            exception = exception
        )
        logQueue.add(entry)
        
        // Keep only last MAX_LOGS entries
        while (logQueue.size > MAX_LOGS) {
            logQueue.poll()
        }
    }
}
