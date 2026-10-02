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
- **Your Library** has filter chips for Songs, Albums, Artists, Composers,
  Folders and Playlists. The ⋮ menu has **Find duplicate songs**.
- **Music directors** (Composers): Tamil and other film songs list the
  singers as the artist, so this groups songs by the Composer tag instead
  (Ilaiyaraaja, A.R. Rahman, Anirudh…). Each music director's page shows
  their movies and albums, newest first, then all their songs. A song's ⋮
  menu has **Go to music director**, and Search finds them too.
- **Smart playlists** under Playlists fill themselves: **Most played**,
  **Recently added** (last 30 days), **Not played in a while** (played
  before, but not in 2 months) and **Never played**.
- **Duplicate finder**: lists songs saved more than once (same title and
  artist, about the same length), e.g. from both WhatsApp and Telegram. Play
  each copy to check it, then delete the extra one. Android asks you to
  confirm each delete. Deleting works on Android 11 and later, for songs in
  the phone's music library.
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
- **Lyrics**: tap the lyrics button on the full player. Timed lyrics from an
  `.lrc` file scroll along with the song and light up line by line; tap a
  line to jump there. Isaialai reads lyrics saved inside the song file, or an
  `.lrc` file with the same name in the same folder. Android only lets apps
  read `.lrc` files in folders you've added (Library → Folders → Add a folder).
- **Edit Up next**: on the full player's Up next list, drag a song by its ≡
  handle to move it, or swipe it left to take it out of the queue. This works
  with shuffle on too.
- **Speed and pitch**: tap **1×** on the full player. Pick 0.75× to 2×, or
  slide to any speed in between, without changing the key. Pitch moves the
  key up or down by up to 6 semitones (handy for singing along) without
  changing the speed. Both stay until you tap Reset.
- **Settings** (gear on Home, or Library ⋮ → Settings):
  - **Appearance**: Dark, Light, or Match phone. On Android 12 and later,
    **Colours from your wallpaper** uses your phone's Material You colours for
    buttons and highlights. The full player always stays dark, tinted by the
    cover.
  - **Player on the lock screen** (on unless you turn it off): lock your
    phone while Isaialai is open and waking it shows a full-screen player with
    the album art, without unlocking. Tap ♥ there to like the song. Only the player shows there; your
    library and settings need unlocking. (Android doesn't let apps put
    themselves on the lock screen from the background, so this works when
    Isaialai was open. The phone's own lock-screen controls work any time.)
  - **Crossfade**: blend each song into the next over 1–12 seconds, with no
    silence in between. The next song starts early on a second player and
    fades in while the current one fades out. The equaliser applies to both.
  - **Even volume**: turns loud songs down and quiet ones up (by up to 12 dB
    down or 6 dB up), so you don't have to reach for the volume between
    songs. Each song's loudness is measured the first time it plays (a few
    seconds from eight places across it) and remembered. Turning up uses
    Android's loudness enhancer, which keeps loud parts from distorting.
    Settings shows how much the current song was changed.
- **Edit song details**: a song's ⋮ menu → **Edit song details…** to fix
  its title, artist, album or movie, music director or year. Edits are kept
  in Isaialai (the song file isn't changed, so other apps still see the
  original), and **Undo my changes** puts the file's details back.
- **Swipe gestures**:
  - On the mini player: swipe left for the next song, right for the previous one.
  - On the full player: swipe the cover to change song, and swipe down to close.
- **Android Auto**: Isaialai shows up in Android Auto (and cars with Android
  built in). Browse Recently played, Liked songs, Playlists, Albums & movies,
  Artists, Music directors or All songs on the car's screen; picking a song
  plays the rest of that list after it. Voice requests ("play Ilaiyaraaja on
  Isaialai") play the songs that match. Android Auto only lists apps from
  the Play Store unless you turn on *Unknown sources* in Android Auto's
  developer settings (open Android Auto settings, tap *Version* ten times,
  then ⋮ → Developer settings → Unknown sources).
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

1. **build**: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK),
   then signs the APK with Isaialai's private key (see [SECURITY.md](SECURITY.md)).
2. **smoke-test**: first checks the APK installs as an update over a build
   signed with the old key, and that an APK signed with only the old public
   key can't replace it. Then installs the APK on an Android 14 emulator, copies test
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

Early builds were signed with `signing/sideload.keystore`, a key whose password
is public, so anyone could have made an APK that installs over Isaialai. Builds
are now signed with a private key kept encrypted in `signing/release.keystore.gpg`,
using Android's key rotation so phones with an older build update without
uninstalling. [SECURITY.md](SECURITY.md) has the details and the one-time setup.
Only install builds from this repository's releases.
