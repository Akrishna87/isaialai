package io.github.akrishna87.mymusic.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mymusic.Effects
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.ResumeMode
import io.github.akrishna87.mymusic.Screen
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MusicViewModel) {
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingsHeading("Appearance") }
            item { AppearanceCard() }
            item { SettingsHeading("Playback") }
            item { CrossfadeCard(vm) }
            item { ResumeCard(vm) }
            item { EvenVolumeCard(vm) }
            item { SettingsCard { BoostControls(vm) } }
            item { SettingsHeading("Backup & restore") }
            item { BackupCard(vm) }
            item { SettingsHeading("Tools") }
            item { LinkRow(Icons.Rounded.Tune, "Equalizer", "Presets, bands and bass boost") { vm.showEqualizer = true } }
            item { LinkRow(Icons.Rounded.ContentCopy, "Find duplicate songs", "Songs saved more than once") { vm.open(Screen.Duplicates) } }
        }
    }
}

@Composable
fun SettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Elevated).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    SettingsCard {
        Text("Theme", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = ThemeSettings.mode == mode,
                    onClick = { ThemeSettings.update(context, mode = mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text("The full player always stays dark, tinted by the song's cover.", color = Palette.SubText, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            "Player on the lock screen",
            "If you lock your phone while Isaialai is open, waking it shows the player with the album art, " +
                "without unlocking. Your library and settings still need unlocking.",
            checked = ThemeSettings.lockScreenPlayer,
        ) { ThemeSettings.setLockScreenPlayer(context, it) }
        if (Build.VERSION.SDK_INT >= 31) {
            SwitchRow(
                "Colours from your wallpaper",
                "Use your phone's Material You colours for buttons and highlights.",
                checked = ThemeSettings.wallpaperColors,
            ) { ThemeSettings.update(context, wallpaperColors = it) }
        }
    }
}

@Composable
private fun CrossfadeCard(vm: MusicViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.roundToInt() ?: vm.crossfadeSec
    SettingsCard {
        Text(
            if (shown == 0) "Crossfade: off" else "Crossfade: $shown s",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            "Blend the end of each song into the start of the next, with no silence in between.",
            color = Palette.SubText,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 3, 5, 8, 12).forEach { sec ->
                FilterChip(
                    selected = vm.crossfadeSec == sec,
                    onClick = { vm.setCrossfade(sec) },
                    label = { Text(if (sec == 0) "Off" else "$sec s") },
                )
            }
        }
        Slider(
            value = dragging ?: vm.crossfadeSec.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { vm.setCrossfade(it.roundToInt()) }; dragging = null },
            valueRange = 0f..12f,
            steps = 11,
            modifier = Modifier.semantics { contentDescription = "Crossfade length" },
        )
    }
}

@Composable
private fun ResumeCard(vm: MusicViewModel) {
    SettingsCard {
        Text("Continue where you left off", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            when (vm.resumeMode) {
                ResumeMode.OFF -> "Songs always start from the beginning."
                ResumeMode.LONG -> "Tracks of 10 minutes or more (talks, concerts, podcasts) continue where you stopped them."
                ResumeMode.ALL -> "Every song continues where you stopped it. Tap ⏮ to start one over."
            },
            color = Palette.SubText,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ResumeMode.entries.forEach { mode ->
                FilterChip(selected = vm.resumeMode == mode, onClick = { vm.chooseResumeMode(mode) }, label = { Text(mode.label) })
            }
        }
    }
}

@Composable
private fun EvenVolumeCard(vm: MusicViewModel) {
    SettingsCard {
        SwitchRow(
            "Even volume",
            "Turns loud songs down and quiet ones up, so you don't have to reach for the volume between songs. " +
                "Each song is measured the first time it plays.",
            checked = vm.evenVolume,
            onChange = vm::setEvenVolumeOn,
        )
        val now = vm.evenVolumeNow
        if (vm.evenVolume && now != null) {
            val (title, db) = now
            val amount = String.format(Locale.US, "%.1f", abs(db))
            Text(
                when {
                    db <= -0.05f -> "Now playing “$title”: turned down $amount dB"
                    db >= 0.05f -> "Now playing “$title”: turned up $amount dB"
                    else -> "Now playing “$title”: already at the right level"
                },
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Elevated).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Elevated2), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Palette.SubText, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = Palette.SubText, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.SubText)
    }
}

/** A setting that's on or off: tap anywhere on the row to switch it. */
@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = title }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = Palette.SubText, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null, // the whole row switches it
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = Color.White),
        )
    }
}

/** Volume boost, like VLC's: 100% to 200%. Shared by Settings and the full player. */
@Composable
fun BoostControls(vm: MusicViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { (it / 10).roundToInt() * 10 } ?: vm.boostPercent
    Text(if (shown <= 100) "Volume boost: off" else "Volume boost: $shown%", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        "Makes music louder than the phone's maximum volume, like VLC's audio boost. " +
            "Loud parts are kept from distorting.",
        color = Palette.SubText,
        fontSize = 13.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(100, 125, 150, 175, 200).forEach { p ->
            FilterChip(
                selected = vm.boostPercent == p,
                onClick = { vm.setBoost(p) },
                label = { Text(if (p == 100) "Off" else "$p%") },
                enabled = vm.boostAvailable,
            )
        }
    }
    Slider(
        value = dragging ?: vm.boostPercent.toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { vm.setBoost((it / 10).roundToInt() * 10) }; dragging = null },
        valueRange = 100f..Effects.MAX_BOOST.toFloat(),
        steps = (Effects.MAX_BOOST - 100) / 10 - 1,
        enabled = vm.boostAvailable,
        modifier = Modifier.semantics { contentDescription = "Volume boost" },
    )
    if (!vm.boostAvailable) {
        Text("This phone doesn't let apps boost the volume.", color = Palette.SubText, fontSize = 13.sp)
    } else if (shown > 150) {
        Text(
            "Careful: very loud sound can hurt your hearing and small speakers. Turn it down if you hear crackling.",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
        )
    }
}
