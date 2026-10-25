package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.MusicViewModel
import kotlinx.coroutines.delay

/** Pick a TV to play on (or this phone). TVs are looked for only while this is open. */
@Composable
fun CastDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    val cast = vm.cast
    DisposableEffect(Unit) {
        cast.startLooking()
        onDispose { cast.stopLooking() }
    }
    // Finding TVs takes a few seconds; after that, say if there are none.
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(8_000)
        searching = false
    }
    // Close once connected, so the music can be heard starting on the TV.
    val connected = cast.connectedTo
    var wasConnected by remember { mutableStateOf(connected != null) }
    LaunchedEffect(connected) {
        if (connected != null && !wasConnected) onDismiss()
        wasConnected = connected != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Cast, contentDescription = null) },
        title = { Text("Play on") },
        text = {
            Column {
                if (connected != null) {
                    Text("Playing on $connected", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { cast.disconnect(); onDismiss() }) { Text("Stop casting") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "When you stop, the music carries on from the same place on this phone (paused).",
                        color = Palette.SubText,
                        fontSize = 13.sp,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        item {
                            DeviceRow(Icons.Rounded.PhoneAndroid, "This phone", "Playing here", selected = true) { onDismiss() }
                        }
                        items(cast.tvs, key = { it.id }) { tv ->
                            val busy = cast.connecting == tv.name
                            DeviceRow(Icons.Rounded.Cast, tv.name, if (busy) "Connecting…" else tv.description, selected = false) {
                                if (cast.connecting == null) cast.connect(tv)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        cast.tvs.isNotEmpty() -> Unit
                        searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Looking for TVs on your Wi-Fi…", color = Palette.SubText, fontSize = 14.sp)
                        }
                        else -> Text(
                            "No TVs found. Check the TV is on (Chromecast, Google TV, Android TV or a TV with " +
                                "Chromecast built in) and on the same Wi-Fi as this phone.",
                            color = Palette.SubText,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "On the TV, the equalizer, boost, crossfade, even volume and autoplay don't apply. " +
                        "Songs go straight from this phone to the TV over your Wi-Fi.",
                    color = Palette.SubText,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Google TV or Android TV? Isaialai's TV app lets you browse playlists and use the TV remote: " +
                        "see Settings → Play on TV app.",
                    color = Palette.SubText,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, color = Palette.SubText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
