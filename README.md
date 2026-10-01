# 🎵 My Music for Android

A native Android music player that plays the songs already saved on your
phone **straight from storage**. There's nothing to pick or import and no
copies, uploads, ads or account. New songs you download show up by
themselves.

## Installing it

1. On your Android phone, open the
   [latest build](https://github.com/Akrishna87/Akrishna87/releases/tag/music-player-latest)
   and tap **MyMusic.apk** to download it.
2. Open the downloaded file. Android will ask you to allow installing apps
   from your browser (or Files app). Allow it, then tap **Install**.
   Google Play Protect may warn that it doesn't recognise the app, because
   it isn't from the Play Store. Tap **More details → Install anyway**.
3. Open **My Music** and tap **Allow access to music**.

To update, install a newer `MyMusic.apk` the same way. It installs over the
old one and keeps your playlists.

## Features

- **Songs / Albums / Artists / Folders / Playlists** tabs, plus search. The
  **Folders** tab mirrors where the files are on your phone (Music, Download,
  WhatsApp Audio and so on).
- **Background playback** with a media notification, lock-screen controls,
  and Bluetooth/headphone/car buttons. It pauses when headphones are
  unplugged or a call comes in.
- **Shuffle**, **repeat** (all / one), and a song's ⋮ menu for **Play next**,
  **Add to queue** and **Add to playlist**. The ☰ button on the full player
  shows **Up next**, and you can tap any song there to jump to it.
- **Picks up where you left off**: same queue, same song, same position, even
  after the phone restarts. Pressing play on headphones or in the car while the
  app is closed resumes it.
- Cover art comes from the songs themselves. Dark and light themes follow your
  phone's setting.
- **Rescan** (↻ at the top) if songs you've just copied don't show up yet.
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
| `Library.kt` | Song model, the MediaStore scan, album/artist/folder grouping |
| `PlaybackService.kt` | The player, media notification, queue/shuffle handling, resume |
| `MusicViewModel.kt` | Connects the screens to the player and library |
| `ui/App.kt` | Permission screen, tabs, detail pages, mini player |
| `ui/NowPlaying.kt` | Full-screen player and Up next |

## Building

GitHub Actions builds it (`.github/workflows/music-player-apk.yml`) on every
push that touches this folder:

1. **build**: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK).
2. **smoke-test**: installs the APK on an Android 14 emulator, copies three
   test songs onto it, and checks that the app lists them, plays them, opens
   the full player, responds to the media "next" button and keeps playing in
   the background. Screenshots are saved with the build.
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
