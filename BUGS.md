# Bug log

GitHub Issues are turned off for this repository, so bugs in the Android app are tracked here.
Each entry has the report, what was found, the fix, and the automated test that guards it.

## BUG-1: Shuffle is not working

- **Reported:** 2026-10-01, on a real phone with build 14 (`claude/music-extras`).
- **Status:** fixed, pending confirmation on the phone.
- **Where shuffle is used:**
  - the shuffle button on the full player
  - *Shuffle all* on Home and in Library → Songs
  - the shuffle icon on album, artist, playlist and folder pages

**What was found**

- When shuffle was on, the full player showed it only as a coral-tinted icon. That
  player's background is tinted with the cover colour, which is often coral or red, so
  on and off looked almost the same. Tapping the button seemed to do nothing.
- There was no other feedback (no message), and the repeat button had the same problem.

**Fix**

- Shuffle and repeat are now bright white with a dot underneath when on, and dimmed
  when off.
- Tapping either one shows a message: "Shuffle on" / "Shuffle off", "Repeating all
  songs" and so on.

**Test**

`ci/smoke-test.sh`, section "Shuffle", runs on an Android 14 emulator against a
12-song album. Twelve songs are enough that a shuffled order can't look in order by
chance. It checks that:

1. Shuffling the album starts playback, and the player shows *Shuffle on*.
2. *Up next* is not in album order.
3. Pressing next three times plays exactly the songs *Up next* listed, in that order.
4. Turning shuffle off switches *Up next* to album order after the current song.
5. Turning shuffle back on reshuffles *Up next* and keeps the current song playing.
