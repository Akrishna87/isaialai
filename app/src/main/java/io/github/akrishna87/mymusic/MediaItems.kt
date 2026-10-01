package io.github.akrishna87.mymusic

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.ifEmpty { null })
            .setArtworkUri(ArtLoader.artworkUriFor(this))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    )
    .build()

/**
 * Rebuilds a media item handed to the player from its song id alone, or returns null when the id
 * names something that isn't one of your songs. (Items lose their file location on the way from
 * the screen to the player anyway, and other apps can send items too, so nothing else is trusted.)
 */
fun MediaItem.trustedOrNull(trusted: TrustedUris): MediaItem? {
    if (mediaId.isEmpty()) return null
    val uri = Song.uriForId(mediaId)
    if (!trusted.isSong(uri)) return null
    val art = mediaMetadata.artworkUri?.takeIf { trusted.isArt(it) }
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(mediaMetadata.buildUpon().setArtworkUri(art).build())
        .build()
}

/** Just enough of a song to play it and show it in the notification. */
fun songStub(id: String, title: String, artist: String, album: String, albumId: Long = 0): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumKey = "",
    albumId = albumId,
    durationMs = 0,
    track = 0,
    dateAdded = 0,
    folder = "",
    fileName = "",
    locationKey = "",
)

fun Song.toBundle(): Bundle = Bundle().apply {
    putString("id", id)
    putString("title", title)
    putString("artist", artist)
    putString("album", album)
    putLong("albumId", albumId)
}

fun songFromBundle(b: Bundle): Song = songStub(
    id = b.getString("id").orEmpty(),
    title = b.getString("title").orEmpty(),
    artist = b.getString("artist").orEmpty(),
    album = b.getString("album").orEmpty(),
    albumId = b.getLong("albumId"),
)
