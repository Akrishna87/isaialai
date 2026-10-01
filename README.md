# 🎧 Isaialai (இசையலை) for Android

*Isaialai* means "music wave" in Tamil: **isai** (music) + **alai** (wave).
The app was first called "My Music". Its internal ID is unchanged, so new
builds install over the old one and keep your playlists and settings.

A native Android music player that plays the songs already saved on your
phone **straight from storage**. There's nothing to pick or import and no
copies, uploads, ads or account. New songs you download show up by
themselves.

## Installing it

1. On your Android phone, open the
   [latest build](https://github.com/Akrishna87/Akrishna87/releases/tag/music-player-latest)
   and tap **Isaialai.apk** to download it. The same file is also there as
   `MyMusic.apk`, so older links keep working.
2. Open the downloaded file. Android will ask you to allow installing apps
   from your browser (or Files app). Allow it, then tap **Install**.
   Google Play Protect may warn that it doesn't recognise the app, because
   it isn't from the Play Store. Tap **More details → Install anyway**.
3. Open **Isaialai** and tap **Allow access to music**.

To update, install a newer `Isaialai.apk` the same way. It installs over the
old one and keeps your playlists.

## Features

- **Home · Search · Library** along the bottom, with a mini player above
  them tinted in the current song's cover colour.
- **Home** greets you by time of day and has quick tiles (Liked songs,
  Shuffle all, recent albums), plus rows for **Jump back in** (recently
  played), **Recently added**, **Your artists**, **On repeat** (most played)
  and **Rediscover** (a different handful of albums each day).
- **Search** matches songs, artists, albums and folders as you type. With an
  empty search box it shows colourful browse tiles for your top artists,
  folders and albums.
- **Your Library** has filter chips for Songs, Albums, Artists, Folders and
  Playlists.
- **Album, artist and playlist pages** show a big cover over a background
  coloured from the artwork, a round Play button, Shuffle, numbered album
  tracks and the total length.
- **Full player** with a background tinted by the cover, a cover that eases
  back when paused, a slim seek bar, a ♥ like button and "Playing from …".
- **Equaliser**: tap the sliders icon on the full player or on Home. Turn it on,
  pick one of the phone's presets (Normal, Pop, Rock, Jazz…) or drag each band
  on the curve. There's also a **Bass boost** slider where the phone supports
  it. Settings are remembered and apply straight away, even in the background.
  Some phones don't let apps use their equaliser; the screen says so if yours
  doesn't.
- **Sleep timer**: tap the moon on the full player. Choose 15, 30 or 45
  minutes, 1 hour, or the end of the current song. The music fades out
  gently, and the time left shows next to the moon.
- **Swipe gestures**:
  - On the mini player: swipe left for the next song, right for the previous one.
  - On the full player: swipe the cover to change song, and swipe down to close.
- **Home-screen widget**: long-press your home screen → Widgets → Isaialai.
  It shows the cover, title and artist with ⏮ ⏯ ⏭, works when the app is
  closed, and opens the app when you tap it.
- **Liked songs**: tap ♥ on the mini player or the full player, or use any
  song's ⋮ menu. Liked songs is a playlist at the top of your Library and on
  Home.
- **Folders** works like a file manager. Start at the top (Music, Download,
  SD card…) and tap into subfolders. Every folder has **Play** and
  **Shuffle**, which include everything inside it.
- **Missing songs? Add a folder.** Some apps (Telegram, many downloaders)
  put a hidden `.nomedia` file in their folders, which makes Android's music
  library skip them. Tap **Add a folder** at the bottom of the Folders tab
  and pick the folder. Isaialai then reads it directly, and remembers it.
  Remove it with the ✕ next to its name.
- Every song on the phone is listed, including ones Android marks as "not
  music" (for example in Podcasts or Audiobooks folders). Ringtones,
  alarms, notification sounds, call recordings and clips under 10 seconds
  are left out.
- **Background playback** with a media notification, lock-screen controls,
  and Bluetooth/headphone/car buttons. It pauses when headphones are
  unplugged or a call comes in.
- **Shuffle**, **repeat** (all / one), and a song's ⋮ menu for **Play next**,
  **Add to queue** and **Add to playlist**. The ☰ button on the full player
  shows **Up next**, and you can tap any song there to jump to it.
- **Picks up where you left off**: same queue, same song, same position, even
  after the phone restarts. Pressing play on headphones or in the car while the
  app is closed resumes it.
- Cover art comes from the songs themselves. Songs without art get their own
  colour gradient, based on the album. The app is always dark.
- **Rescan** (↻ on Home) if songs you've just copied don't show up yet.
  Usually they appear on their own within a few seconds.

Plays whatever Android can: MP3, M4A/AAC, FLAC, Ogg/Opus, WAV and more.
Requires Android 8.0 or newer.

## How it's built

- Kotlin, Jetpack Compose (Material 3) for the screens.
- [Media3](https://developer.android.com/media/media3) ExoPlayer +
  `MediaSessionService` for playback, the notification and lock screen.
- Songs come from Android's MediaStore (`MusicRepository` in `Library.kt`), so
  the app reads files in place; it only needs the "Music and audio"
  permission.
- Playlists and the saved queue are stored in the app's SharedPreferences.

| File | What's in it |
| --- | --- |
| `Library.kt` | Song model, the MediaStore scan, album/artist grouping, the folder tree |
| `AddedFolders.kt` | Folders added with the folder picker: reading them directly, tag cache |
| `PlaybackService.kt` | The player, media notification, queue/shuffle handling, resume |
| `MusicViewModel.kt` | Connects the screens to the player and library |
| `ui/App.kt` | Permission screen, bottom tabs, mini player, screen switching |
| `ui/HomeScreen.kt`, `ui/SearchScreen.kt`, `ui/LibraryScreen.kt` | The three tabs (Library includes the folder tree and "Add a folder") |
| `ui/DetailScreen.kt` | Album, artist, playlist and folder pages |
| `ui/NowPlaying.kt` | Full-screen player and Up next |
| `ui/ArtColors.kt` | Picks each cover's colour (androidx.palette) for the tinted backgrounds |
| `ui/EqualizerScreen.kt`, `Effects.kt` | Equaliser screen and its settings; `PlaybackService` owns the effects and the sleep timer |
| `PlayerWidget.kt` | Home-screen widget and its buttons |
| `ci/icon/gen.py` | Generates the launcher icon (the vibing listener, plus the themed one-colour version) as vector drawables |

## Building

GitHub Actions builds it (`.github/workflows/music-player-apk.yml`) on every
push that touches this folder:

1. **build**: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK).
2. **smoke-test**: installs the APK on an Android 14 emulator, copies test
   songs onto it, and checks that the app lists them (including one Android
   marks as "not music"), plays them, opens the full player, responds to the
   media "next" button and keeps playing in the background. It also browses
   into subfolders, adds a hidden `.nomedia` folder through Android's folder
   picker, and plays a song from it. Screenshots are saved with the build.
3. **publish**: replaces the `music-player-latest` release with the new APK
   and the screenshots.

To build locally instead, open this folder in Android Studio, or run
`./gradlew assembleRelease` with `ANDROID_HOME` pointing at an Android SDK.

### About the signing key

`signing/sideload.keystore` (password `mymusic`) is committed on purpose. It
lets every build install as an update over the last one, which matters for a
sideloaded app. Since it's public, it doesn't prove who built the APK. Only
install builds from this repository's releases. If you ever publish the app
more widely, make a private key and supply it through the `SIGNING_KEYSTORE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`
environment variables (for example from GitHub secrets). `app/build.gradle.kts`
uses them when they're set.
