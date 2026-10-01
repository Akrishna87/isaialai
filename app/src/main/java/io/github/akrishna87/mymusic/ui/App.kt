package io.github.akrishna87.mymusic.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akrishna87.mymusic.MusicViewModel
import io.github.akrishna87.mymusic.Section

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

@Composable
fun MusicApp(vm: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) denied = true
    }
    // The person may have allowed access from system settings while we were in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAudioPermission(context) }
    LaunchedEffect(granted) { if (granted) vm.onPermissionGranted() }

    if (granted) {
        Shell(vm)
    } else {
        PermissionScreen(
            denied = denied,
            onAsk = { launcher.launch(AUDIO_PERMISSION) },
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
        )
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.Violet.deep(0.35f), Palette.Background, Palette.Background))),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(Icons.Rounded.LibraryMusic, null, Modifier.size(112.dp), RoundedCornerShape(28.dp), iconSize = 56.dp)
            Spacer(Modifier.height(32.dp))
            Text("Your music,\nright here.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(
                "My Music plays the songs already saved on this phone, straight from storage. " +
                    "Nothing is copied or uploaded.",
                textAlign = TextAlign.Center,
                color = Palette.SubText,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onAsk,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
            ) { Text("Allow access to music", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (denied) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                    Text("Open settings")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "If the button above does nothing, open settings → Permissions → Music and audio → Allow.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.SubText,
                )
            }
        }
    }
}

/** Bottom tabs, the mini player above them, and whichever screen is showing. */
@Composable
private fun Shell(vm: MusicViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.showPlayer || vm.screens.isNotEmpty() || vm.section != Section.HOME) {
        if (!vm.back()) vm.selectSection(Section.HOME)
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = navInset + 64.dp + (if (vm.currentId != null) 66.dp else 0.dp) + 16.dp

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        CompositionLocalProvider(LocalBottomSpace provides bottomSpace) {
            val top = vm.screens.lastOrNull()
            AnimatedContent(
                targetState = top to vm.section,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { (screen, section) ->
                when {
                    vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    screen != null -> DetailScreen(vm, screen)
                    vm.songs.isEmpty() && section != Section.LIBRARY -> EmptyLibrary(vm)
                    section == Section.HOME -> HomeScreen(vm)
                    section == Section.SEARCH -> SearchScreen(vm)
                    else -> LibraryScreen(vm)
                }
            }
        }

        // Fade the content out behind the tab bar.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            MiniPlayer(vm)
            BottomBar(vm)
        }

        AnimatedVisibility(
            visible = vm.showPlayer && vm.currentId != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlaying(vm)
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSpace - 8.dp),
        )
    }

    vm.playlistPickerFor?.let { PlaylistPickerDialog(vm, it) }
    vm.nameRequest?.let { req -> NameDialog(req) { vm.nameRequest = null } }
}

@Composable
private fun BottomBar(vm: MusicViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Background.copy(alpha = 0.92f), Palette.Background))),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, vm.section == Section.HOME && vm.screens.isEmpty()) {
                vm.selectSection(Section.HOME)
            }
            NavItem("Search", Icons.Rounded.Search, Icons.Rounded.Search, vm.section == Section.SEARCH && vm.screens.isEmpty()) {
                vm.selectSection(Section.SEARCH)
            }
            NavItem("Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, vm.section == Section.LIBRARY && vm.screens.isEmpty()) {
                vm.selectSection(Section.LIBRARY)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, selectedIcon: ImageVector, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Palette.Text else Palette.Faint
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A floating card tinted with the cover's colour, like the big music apps. */
@Composable
private fun MiniPlayer(vm: MusicViewModel) {
    if (vm.currentId == null) return
    val song = vm.currentSong
    val color = rememberArtColor(song)
    val fraction = if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 2.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(color.deep(0.5f))
                .clickable { vm.showPlayer = true },
        ) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ArtImage(song, Modifier.size(44.dp), shape = RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: vm.currentTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(song?.artist ?: vm.currentArtist, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                if (song != null) {
                    val liked = vm.isLiked(song)
                    IconButton(onClick = { vm.toggleLike(song) }) {
                        Icon(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (liked) "Remove from Liked songs" else "Like",
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
                IconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (vm.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun EmptyLibrary(vm: MusicViewModel) {
    val pickFolder = rememberFolderPicker(vm)
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(Icons.Rounded.LibraryMusic, null, Modifier.size(96.dp), RoundedCornerShape(24.dp), iconSize = 48.dp)
        Spacer(Modifier.height(24.dp))
        Text("No music found yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Songs saved anywhere on the phone (Music, Download and so on) show up here by themselves. " +
                "If you've just copied some over, tap Rescan.",
            textAlign = TextAlign.Center,
            color = Palette.SubText,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.refreshLibrary(announce = true) }, shape = CircleShape) { Text("Rescan") }
            Button(onClick = pickFolder, shape = CircleShape) { Text("Add a folder") }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Music in a folder Android hides (Telegram, some downloader apps)? Tap “Add a folder” and pick it.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.SubText,
        )
    }
}
