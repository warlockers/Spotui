package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SwipeToQueue(song: SongsModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                val added = playerViewModel.addToQueue(song)
                android.widget.Toast.makeText(
                    context, if (added) "Added to queue" else "Already in queue", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            val revealed = with(androidx.compose.ui.platform.LocalDensity.current) {
                runCatching { state.requireOffset() }.getOrDefault(0f).coerceAtLeast(0f).toDp()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxHeight().width(revealed).background(Color(0xFF1ED760))
                    .padding(start = 20.dp).clipToBounds(),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Add to queue", tint = Color.Black, modifier = Modifier.size(24.dp))
            }
        },
    ) { content() }
}
