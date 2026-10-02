package io.github.akrishna87.mymusic

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * The only places the player will read from. Other apps can talk to the player (headphones,
 * car, lock screen), so anything that names a file is checked against this list first:
 * songs in the phone's music library, their album art, and songs inside folders you added.
 */
class TrustedUris(private val context: Context) {

    // Read once per check, not once per song: a queue can hold thousands.
    private val addedFolders: List<Uri> by lazy { AddedFolders.list(context) }

    /** A song the player may open. */
    fun isSong(uri: Uri): Boolean = isLibrarySong(uri) || isInAddedFolder(uri)

    /** Cover art the notification may load. */
    fun isArt(uri: Uri): Boolean = isSong(uri) || isLibraryAlbumArt(uri) || CustomArt.isCoverUri(uri)

    /** content://media/<volume>/audio/media/<id> */
    private fun isLibrarySong(uri: Uri): Boolean = isMediaStore(uri, "media")

    /** content://media/<volume>/audio/albumart/<id> */
    private fun isLibraryAlbumArt(uri: Uri): Boolean = isMediaStore(uri, "albumart")

    private fun isMediaStore(uri: Uri, table: String): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return false
        if (uri.query != null || uri.fragment != null) return false
        val s = uri.pathSegments
        return s.size == 4 && s[1] == "audio" && s[2] == table && s[3].toLongOrNull() != null
    }

    /** A document reached through a folder you picked (and the app still holds read access to). */
    private fun isInAddedFolder(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.query != null || uri.fragment != null) return false
        // content://<provider>/tree/<folder id>/document/<file id>; the provider itself refuses
        // file ids that aren't inside the folder.
        val s = uri.pathSegments
        if (s.size != 4 || s[0] != "tree" || s[2] != "document") return false
        return try {
            val tree = DocumentsContract.getTreeDocumentId(uri)
            addedFolders.any { granted ->
                granted.authority == uri.authority && DocumentsContract.getTreeDocumentId(granted) == tree
            }
        } catch (e: Exception) {
            false
        }
    }
}
