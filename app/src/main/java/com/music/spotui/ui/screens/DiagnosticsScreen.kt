package com.music.spotui.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.utils.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    var loggingEnabled by remember { mutableStateOf(StreamLogger.isEnabled()) }
    var logs by remember { mutableStateOf(StreamLogger.getLogs()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showExportSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Refresh logs periodically
        while (true) {
            kotlinx.coroutines.delay(1000)
            logs = StreamLogger.getLogs()
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs?") },
            text = { Text("All diagnostic logs will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        StreamLogger.clearLogs()
                        logs = emptyList()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                Button(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            // Header
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Stream Diagnostics",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Switch(
                            checked = loggingEnabled,
                            onCheckedChange = { enabled ->
                                loggingEnabled = enabled
                                StreamLogger.setEnabled(context, enabled)
                            },
                            modifier = Modifier.scale(0.8f)
                        )
                    }

                    Text(
                        "Logs: ${logs.size} entries",
                        color = Color(0xFFBBBBBB),
                        fontSize = 12.sp
                    )

                    if (loggingEnabled) {
                        Text(
                            "● Recording enabled",
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            "○ Recording disabled",
                            color = Color(0xFFFF9800),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Control buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showClearDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            StreamLogger.exportLogs(context)
                            showExportSuccess = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Export", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export", fontSize = 12.sp)
                }

                Button(
                    onClick = { logs = StreamLogger.getLogs() },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh", fontSize = 12.sp)
                }
            }

            // Logs list
            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "No logs",
                        tint = Color(0xFF666666),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No logs yet",
                        color = Color(0xFFBBBBBB),
                        fontSize = 14.sp
                    )
                    Text(
                        if (loggingEnabled) "Logs will appear when playing music" else "Enable logging to see stream diagnostics",
                        color = Color(0xFF666666),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    items(logs.reversed()) { entry ->
                        LogEntryCard(entry)
                    }
                }
            }
        }

        if (showExportSuccess) {
            Snackbar(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text("Logs exported successfully")
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showExportSuccess = false
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: StreamLogger.LogEntry) {
    val levelColor = when (entry.level) {
        "ERROR" -> Color(0xFFEF5350)
        "WARN" -> Color(0xFFFFA726)
        "BUFFER", "PLAYBACK" -> Color(0xFF42A5F5)
        "RESOLVE" -> Color(0xFF66BB6A)
        "NETWORK" -> Color(0xFFAB47BC)
        "TIMEOUT" -> Color(0xFFFF7043)
        else -> Color(0xFF90CAF9)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(6.dp)
                                .padding(end = 6.dp),
                            color = levelColor,
                            shape = RoundedCornerShape(50)
                        ) {}

                        Text(
                            entry.level,
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        Text(
                            entry.tag,
                            color = Color(0xFFBBBBBB),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        entry.message,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 12.dp)
                    )

                    if (entry.exception != null) {
                        Text(
                            entry.exception,
                            color = Color(0xFFEF5350),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .padding(top = 4.dp),
                            maxLines = 3
                        )
                    }
                }
            }

            Text(
                entry.format().split("]")[0] + "]",
                color = Color(0xFF666666),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp)
            )
        }
    }
}

@Composable
private fun Modifier.scale(scale: Float): Modifier {
    return this.then(
        Modifier.graphicsLayer(
            scaleX = scale,
            scaleY = scale
        )
    )
}
