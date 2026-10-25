package io.github.akrishna87.mymusic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.TvShare

/** Settings → Play on TV app: share this phone's music with Isaialai on a Google TV / Android TV. */
@Composable
fun TvShareCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TvShare.loadPaired(context) }
    SettingsCard {
        SwitchRow(
            "Share with your TV",
            "Lets Isaialai on your Google TV or Android TV browse and play this phone's music over your Wi-Fi",
            TvShare.sharing,
        ) { on -> if (on) TvShare.start(context) else TvShare.stop(context) }
        val at = TvShare.address
        if (TvShare.sharing && at != null) {
            Text(
                "Sharing on Wi-Fi at $at. On the TV, open Isaialai and pick this phone " +
                    "(${TvShare.phoneName(context)}). It stops by itself after 30 minutes without the TV.",
                color = Palette.SubText,
                fontSize = 13.sp,
            )
        }
        TvShare.stopReason?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (TvShare.paired.isNotEmpty()) {
            Text("TVs you've allowed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            TvShare.paired.forEach { tv ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tv.name, Modifier.weight(1f))
                    TextButton(onClick = { TvShare.forget(context, tv) }) { Text("Forget") }
                }
            }
        }
        Text(
            "Getting Isaialai on the TV: on the TV, install \"Downloader\" from the Play Store, allow it to " +
                "install apps, and open the same download link as on the phone. The TV app finds this " +
                "phone while sharing is on; the TV shows a code, and you allow it here once.",
            color = Palette.SubText,
            fontSize = 12.sp,
        )
    }
}

/** A TV is asking to play this phone's music: shown over any screen until you answer. */
@Composable
fun TvPairDialog() {
    val ask = TvShare.waiting.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Allow ${ask.tvName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${ask.tvName} wants to browse and play the music on this phone over your Wi-Fi.")
                Text("Check the TV shows this code:", color = Palette.SubText, fontSize = 14.sp)
                Text(ask.code, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 8.sp)
                Text("If it doesn't, or you didn't just pick this phone on a TV, don't allow it.", color = Palette.SubText, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { TvShare.allow(ask) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { TvShare.refuse(ask) }) { Text("Don't allow") } },
    )
}
