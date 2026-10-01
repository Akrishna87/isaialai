package io.github.akrishna87.mymusic

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
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

/** Media items sent from the screen to the player lose their file location on the way; put it back. */
fun MediaItem.withPlayableUri(): MediaItem {
    if (localConfiguration != null) return this
    val id = mediaId.toLongOrNull() ?: return this
    return buildUpon().setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)).build()
}

fun Song.toBundle(): Bundle = Bundle().apply {
    putLong("id", id)
    putString("title", title)
    putString("artist", artist)
    putString("album", album)
    putLong("albumId", albumId)
}

fun songFromBundle(b: Bundle): Song = Song(
    id = b.getLong("id"),
    title = b.getString("title").orEmpty(),
    artist = b.getString("artist").orEmpty(),
    album = b.getString("album").orEmpty(),
    albumId = b.getLong("albumId"),
    durationMs = 0,
    track = 0,
    dateAdded = 0,
    folder = "",
    fileName = "",
)
