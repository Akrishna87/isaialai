# Security

Results of a code review of Isaialai and what was changed.

## What was already fine

- **No internet access.** The APK has no `INTERNET` permission, so it can't
  send your music, play history or anything else off the phone.
  (`ACCESS_NETWORK_STATE` comes in from the Media3 library and only reads
  whether a network is connected.)
- **Not debuggable**, and release builds are minified.
- **Limited storage access.** It asks for `READ_MEDIA_AUDIO` (or
  `READ_EXTERNAL_STORAGE` on Android 12 and older) and read-only access to
  folders you add. It never changes your existing files. It only adds new
  ones: cut songs and ringtones you ask for, and its backup folder (below),
  saved through Android's media library (`WRITE_EXTERNAL_STORAGE` only on
  Android 9 and older). It deletes
  a file only from the duplicate finder, after Android asks you to confirm.
- **Backups stay on the phone, where you can see them.** Automatic backup
  (Settings → Backup & restore, on by default, can be turned off) writes
  playlists, liked songs, play counts and history, song edits and settings to
  `Download/Isaialai`. Nothing is uploaded. Like any file in Download, other
  apps with access to all your files can read it. Restoring only reads the
  folder or file you pick, and treats it as data: playlist and backup files
  are parsed, never run, are limited to 20 MB, and settings from a backup
  are range-checked. Isaialai keeps access only to the folder you restored
  from, to keep the backup there up to date.
- **Ringtones only with your say-so.** Setting a cut as your ringtone,
  notification or alarm sound uses `WRITE_SETTINGS`. Android keeps that off
  until you turn on "Allow modifying system settings" for Isaialai, and the
  app uses it for nothing else.
- **Song tags shown as text only.** Titles and artists from files are never
  run as code (the web version escapes them before showing them).

## Fixed

### 1. Public signing key (high)

Every build was signed with `signing/sideload.keystore`, committed with its
password. Android trusts updates by signing key, so anyone could have built an
APK that installs over Isaialai *as an update*. It would keep its data and
permissions (access to all your audio and the folders you added) without any
warning.

**Fix:** Isaialai now has a private key. It's stored only encrypted
(`signing/release.keystore.gpg`, AES-256, with a passphrase kept in a GitHub
secret) and only unlocked inside the build. Android's
[APK key rotation](https://source.android.com/docs/security/features/apksigning/v3#key-rotation)
(`signing/rotation.lineage`, signed by the old key) tells phones that the new
key takes over from the old one:

- Phones with an older build **update normally, no uninstall**, and keep
  liked songs, playlists, history and added folders.
- From then on, Android 9+ refuses any APK signed with only the old public key
  as an update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

Each build tests both on an emulator (`ci/update-test.sh`).

**Limit:** Android 8.0 and 8.1 don't understand key rotation and keep
checking the old key. On those phones the old risk remains; uninstalling and
installing a new build fixes it, but loses the app's data.

### 2. Player controls open to every app (medium)

Media players have to accept play/pause/skip from headphones, cars, watches and
the lock screen, so the playback service is open to other apps. But it also
let *any* app:

- queue any file path or content link, which the player then opened with
  Isaialai's own access to your storage and folders;
- point the notification's cover art at any link, loaded the same way;
- use the add-to-queue and sleep-timer commands.

**Fix** (`PlaybackService.kt`, `TrustedUris.kt`):

- Only Isaialai itself (checked by Android user id, which can't be faked) can
  use add-to-queue and the sleep timer.
- Other apps get play, pause, skip, seek, shuffle and repeat. Only apps
  Android trusts with media control (the phone's own media controls, and apps
  you've allowed to read notifications) can also change the queue, so
  "resume" on the lock screen still works.
- Only Isaialai and Android Auto (or a car with Android built in) can list
  your music; any other app asking to browse is refused.
- Whatever is queued, by anyone, must be a song in the phone's music library
  or inside a folder you added. Anything else is dropped. Cover art must be
  one of those songs or the library's album art.

### 3. Backups (low)

The app allowed Android backup, which copies its data (play history, playlists,
which folders you added) to cloud backups and onto new phones.
**Fix:** backups and device-to-device transfer are turned off
(`android:allowBackup="false"`, `res/xml/data_extraction_rules.xml`).
Isaialai's own backup (see above) replaces them: a local folder you can see,
copy or delete, and turn off.

### 4. Build pipeline (low)

The GitHub Actions workflows used third-party actions by version tag, which
their owners can move to different code.
**Fix:** every action is pinned to a full commit SHA, with the version in a
comment. Jobs get read-only repository access except the publish and key-setup
jobs, which need to write.

## One-time key setup

Done once, by the repository owner. The passphrase never goes into a chat, an
issue or a commit.

1. Make up a long random passphrase (at least 20 characters; a password
   manager's generator is ideal) and **save it in your password manager**.
   If it's lost, new builds can't be signed with the same key, and phones
   would have to uninstall to get updates.
2. On GitHub: the repository → **Settings → Secrets and variables → Actions →
   New repository secret**. Name: `SIGNING_PASSPHRASE`, value: the passphrase.
3. Run the **Music player signing key (one-time setup)** workflow
   (`.github/workflows/music-player-signing-key.yml`). It makes the key, writes
   the rotation record, encrypts the key, checks the encrypted copy opens,
   and commits both files. It refuses to run if a key already exists.
4. The next APK build signs with the new key and runs the update test.

Keep `signing/sideload.keystore`: each APK is still signed with the old key
too, for Android 8 phones, alongside the new one.

## Reporting a problem

Open a pull request or contact the repository owner. Please don't post details
of an unfixed problem publicly.
