package com.soniq.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File

class MusicRepository(private val context: Context) {

    fun loadSongs(): List<Song> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = mutableListOf<Song>()
        val artworkBaseUri = Uri.parse("content://media/external/audio/albumart")

        context.contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val relativeCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val songUri = ContentUris.withAppendedId(collection, id)
                
                // Fallback sequence: Android Albumart Uri -> Raw Audio Uri for embedded ID3 tags
                val art = ContentUris.withAppendedId(artworkBaseUri, albumId).toString()

                val path = if (relativeCol >= 0) cursor.getString(relativeCol)
                    else if (dataCol >= 0) cursor.getString(dataCol)?.let { File(it).parent }
                    else null

                result += Song(
                    id = id,
                    title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                    artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                    album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown album",
                    durationMs = cursor.getLong(durationCol),
                    uri = songUri.toString(),
                    albumArtUri = art,
                    folder = path?.trimEnd('/')?.substringAfterLast('/').orEmpty().ifBlank { "Download" },
                    dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                    path = if (dataCol >= 0) cursor.getString(dataCol) else null
                )
            }
        }
        return result
    }

    companion object {
        fun extractEmbeddedPicture(context: Context, audioUriString: String): ByteArray? {
            return runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(audioUriString))
                val picture = retriever.embeddedPicture
                retriever.release()
                picture
            }.getOrNull()
        }
    }
}
