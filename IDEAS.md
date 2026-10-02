# Ideas on hold

Features we've talked about and decided to come back to later. Nothing here is
built yet. Each one notes what has to be decided before work starts.

## Music jam with friends

Listen together and let friends add songs.

**Options discussed**

1. **Party mode, same room (recommended).** Your phone plays the music
   (speaker or Bluetooth). Tap *Start jam* and a QR code appears. Friends on
   the same Wi-Fi or your hotspot scan it, and a page opens in their browser
   (no app to install). From there they can search your songs, add to Up
   next, see what's playing, and maybe vote songs up. You can remove songs,
   skip, or end the jam.
   - Local network only; nothing goes outside it.
   - A one-time code in the QR stops others on the same Wi-Fi joining.
   - Friends can only add to the queue, never download your files.
2. **Every phone plays in sync, same room.** Stream from your phone to theirs
   over Wi-Fi. Hard to keep in step (a small lag sounds like an echo). A
   Bluetooth speaker or the phone's "dual audio" covers most of this already.
3. **Friends elsewhere, over the internet.** Needs a server (cost and
   upkeep). Streaming song files to others raises copyright problems. Sharing
   only the queue works for songs everyone already has.

**To decide first**

- Are friends usually in the same room, or remote?
- Can friends only add songs, or also skip and pause?
- Is it OK for Isaialai to get network access, limited to your own Wi-Fi?
  (Android needs the internet permission even for local Wi-Fi, so the app
  would no longer be "no internet access". SECURITY.md would need updating.)

## Search and download songs from a website

Save a website's address in Isaialai, search it for a movie or song, and
download the MP3 into the phone's Music folder, where it shows up in the
library.

**Things to settle**

- **The site must be licensed to share the songs.** Many free film-song MP3
  sites aren't, and Isaialai won't include a downloader for those. Stores
  you've bought from, artists' or labels' own pages, and free-music sites
  (Jamendo, Free Music Archive, Internet Archive) are fine.
- **Upkeep.** Reading a site's pages breaks whenever the site changes its
  layout.
- **Network access.** Isaialai would need the internet permission. It could
  be limited to the one site you add.

**Already works today:** a song downloaded in the phone's browser lands in
Downloads, and Isaialai finds it automatically.

**Simpler alternative:** a "Download with Isaialai" option in Android's Share
menu. Find the song in your browser, share the link to Isaialai, and it saves
the file to Music and tags it. This works with any site and doesn't break when
sites change, and the app never searches the web itself.

**To decide first:** which site, and the full search feature or the Share-menu
option.
