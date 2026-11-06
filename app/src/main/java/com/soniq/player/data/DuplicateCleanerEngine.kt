package com.soniq.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class DuplicateGroup(
    val cleanTitle: String,
    val songs: List<SongWithBitrate>
)

data class SongWithBitrate(
    val song: Song,
    val bitrateKbps: Int
)

object DuplicateCleanerEngine {

    suspend fun findDuplicates(context: Context, allSongs: List<Song>): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val groups = mutableMapOf<String, MutableList<SongWithBitrate>>()

        for (song in allSongs) {
            try {
                val normalizedTitle = song.title.lowercase()
                    .replace(Regex("""(?i)\(.*?\)|\\[.*?\\]|__?.*|_320kbps|_128kbps|\.mp3"""), "")
                    .replace(Regex("""[^a-zA-Z0-9]"""), "")
                    .trim()

                if (normalizedTitle.length < 3) continue

                val bitrate = getBitrateSafely(context, song.uri)
                val item = SongWithBitrate(song, bitrate)

                var addedToExisting = false
                for ((key, list) in groups) {
                    if (key == normalizedTitle && list.isNotEmpty()) {
                        val firstDuration = list.first().song.durationMs
                        if (abs(firstDuration - song.durationMs) < 3000) {
                            list.add(item)
                            addedToExisting = true
                            break
                        }
                    }
                }

                if (!addedToExisting) {
                    groups.getOrPut(normalizedTitle) { mutableListOf() }.add(item)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        groups.values
            .filter { it.size >= 2 }
            .map { list ->
                val sorted = list.sortedByDescending { it.bitrateKbps }
                DuplicateGroup(sorted.first().song.title, sorted)
            }
    }

    private fun getBitrateSafely(context: Context, uriString: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                retriever.setDataSource(context, uri)
                val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                (brStr?.toIntOrNull() ?: 128000) / 1000
            } else {
                128
            }
        } catch (_: Exception) {
            128
        } finally {
            runCatching { retriever.release() }
        }
    }
}
