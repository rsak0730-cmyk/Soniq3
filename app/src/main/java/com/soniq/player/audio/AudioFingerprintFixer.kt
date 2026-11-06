package com.soniq.player.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.soniq.player.data.MatchUtils
import com.soniq.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class FingerprintResult(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val matchConfidence: Float
)

private data class CatalogCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String?
)

private class EmbeddedRead(val tags: FingerprintResult?, val picture: ByteArray?)

object AudioFingerprintFixer {

    /** A catalog match is only applied when its title, artist and length all agree. */
    private const val MATCH_THRESHOLD = 0.62
    private const val MIN_TITLE_SIM = 0.72
    private const val MIN_ARTIST_SIM = 0.40
    private const val MAX_DURATION_DIFF_MS = 3000L
    private const val MAX_DURATION_DIFF_NO_ARTIST_MS = 2500L
    /** Without an artist tag the title must still agree closely (about 3 of 4 words). */
    private const val MIN_TITLE_SIM_NO_ARTIST = 0.75
    private val SEARCH_NOISE = setOf("official", "video", "full", "audio", "lyric", "lyrics", "song", "hd", "mp3", "feat", "ft")

    /** Catalog requests that failed on the network during the last batch run. */
    @Volatile var lastRunNetworkFailures: Int = 0
        private set
    private val networkFailures = AtomicInteger(0)
    @Volatile private var firstFailure: String? = null
    /** First network or catalog error seen in the last batch run, shown in the summary. */
    @Volatile var lastRunFirstError: String? = null
        private set

    private fun noteFailure(detail: String) {
        networkFailures.incrementAndGet()
        synchronized(this) { if (firstFailure == null) firstFailure = detail }
    }
    private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) SONIQ/1.0"

    private val SPAM_PATTERNS = listOf(
        Regex("""(?i)\[.*?]"""),
        Regex("""(?i)\(.*?\b(official|video|audio|lyric|lyrics|hd|4k|download|mp3|remix|mashup|slowed|reverb|full|song|ringtone)\b.*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song|ringtone).*"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav|\.aac"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)\b(pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp|vevo|tseries|zee\s*music|speed\s*records|download|dhun)\b"""),
        Regex("""(?i)-\s*(non-s.*|non-stop.*|ek\s*dee.*|dj.*|remix.*|lyric.*)""")
    )

    /** Cleans a raw file name. Keeps Devanagari letters so Hindi titles survive. */
    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace("_", " ")
        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }
        cleaned = cleaned.replace(Regex("""[^\p{L}\p{M}\p{N}\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        return cleaned
    }

    fun isSongUnknown(song: Song): Boolean {
        if (song.id < 0) return false
        val unknownWords = listOf("<unknown>", "unknown", "untitled", "various", "download", "original artist", "track", "downlo")
        val artistUnknown = song.artist.isBlank() || unknownWords.any { song.artist.contains(it, ignoreCase = true) }
        val titleIsRaw = song.title.endsWith(".mp3", ignoreCase = true) ||
            unknownWords.any { song.title.equals(it, ignoreCase = true) } ||
            song.title.contains("_") ||
            song.title.contains("download", ignoreCase = true) ||
            song.title.contains("Non-S", ignoreCase = true) ||
            song.title.startsWith("audio_", ignoreCase = true) ||
            song.title.startsWith("Dhun", ignoreCase = true) ||
            song.title.contains("Lyric", ignoreCase = true)
        return artistUnknown || titleIsRaw
    }

    suspend fun autoFixSong(context: Context, song: Song): FingerprintResult = withContext(Dispatchers.IO) {
        val embedded = readEmbedded(context, song)

        // 1. The file's own tags, when they are clean. Artwork comes from the embedded picture,
        //    or from the catalog if the file has no picture.
        val tags = embedded.tags
        if (tags != null) {
            val art = embedded.picture?.let { saveArtBytes(context, song.id, it) }
                ?: findCatalogArt(tags.title, tags.artist, song.durationMs)?.let { saveArtUrl(context, song.id, it) }
            return@withContext FingerprintResult(
                title = tags.title,
                artist = tags.artist,
                album = tags.album,
                albumArtUrl = art ?: song.albumArtUri,
                matchConfidence = 0.90f
            )
        }

        // 2. Catalog search. Every candidate is scored against the file; the best one must clear the bar.
        val localTitle = cleanLocalTitle(song.title)
        val localArtist = if (MatchUtils.isUnknownArtist(song.artist)) "" else cleanLocalTitle(song.artist)
        if (localTitle.length < 3) return@withContext unmatched(song, localTitle)

        var bestCandidate: CatalogCandidate? = null
        var bestScore = 0.0
        // JioSaavn's two previously configured public deployments are currently
        // unavailable (DNS NXDOMAIN / paused deployment). Do not waste a request
        // on them or count their known outages as failures; use the reachable
        // iTunes India catalog as the active source.
        for (query in buildQueries(localTitle, localArtist)) {
            val candidates = queryItunes(query)
            for (c in candidates) {
                val score = scoreCandidate(c, localTitle, localArtist, song.durationMs) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = c
                }
            }
            if (bestScore >= 0.90) break
        }

        val match = bestCandidate
        if (match == null || bestScore < MATCH_THRESHOLD) {
            return@withContext unmatched(song, localTitle)
        }

        val art = match.artUrl?.let { saveArtUrl(context, song.id, it) }
            ?: embedded.picture?.let { saveArtBytes(context, song.id, it) }
            ?: song.albumArtUri
        FingerprintResult(
            title = match.title,
            artist = match.artist.ifBlank { "Unknown artist" },
            album = match.album.ifBlank { "Single" },
            albumArtUrl = art,
            matchConfidence = bestScore.toFloat()
        )
    }

    /**
     * No verified match: keep the original artist and only tidy the title. Confidence 0.55 is
     * the cleaned-title fallback; 0.0 means nothing is applied. Artwork is left as-is here, so an
     * unmatched song never overwrites or deletes an existing cover.
     */
    private fun unmatched(song: Song, cleanedTitle: String): FingerprintResult {
        val words = cleanedTitle.split(" ").filter { it.isNotBlank() }
        val useCleanTitle = words.size >= 2 && !cleanedTitle.equals(song.title, ignoreCase = true)
        return FingerprintResult(
            title = if (useCleanTitle) cleanedTitle else song.title,
            artist = if (MatchUtils.isUnknownArtist(song.artist)) "Unknown artist" else song.artist,
            album = if (song.album.contains("downlo", true)) "Single" else song.album,
            albumArtUrl = song.albumArtUri,
            matchConfidence = if (useCleanTitle) 0.55f else 0.0f
        )
    }

    private fun buildQueries(localTitle: String, localArtist: String): List<String> {
        val queries = LinkedHashSet<String>()
        if (localArtist.isNotBlank()) queries.add("$localTitle $localArtist")
        // Without "Official / Video / Full" the catalog finds the song far more often.
        val core = localTitle.split(" ").filter { it.isNotBlank() && it.lowercase() !in SEARCH_NOISE }.joinToString(" ")
        if (core.length >= 3) queries.add(core)
        queries.add(localTitle)
        val words = localTitle.split(" ").filter { it.isNotBlank() }
        if (words.size >= 3) queries.add(words.take(3).joinToString(" "))
        if (words.size >= 2) queries.add(words.take(2).joinToString(" "))
        return queries.filter { it.length >= 3 }.take(5)
    }

    /** Returns a 0..1 score when the candidate agrees with the file, or null when it does not. */
    private fun scoreCandidate(c: CatalogCandidate, localTitle: String, localArtist: String, localDurMs: Long): Double? {
        val titleSim = MatchUtils.similarity(localTitle, c.title)
        if (titleSim < MIN_TITLE_SIM) return null

        val artistSim = if (localArtist.isBlank()) 0.5 else MatchUtils.artistSimilarity(localArtist, c.artist)
        if (localArtist.isNotBlank() && artistSim < MIN_ARTIST_SIM) return null

        val hasDur = localDurMs > 0 && c.durationMs > 0
        val diff = if (hasDur) abs(localDurMs - c.durationMs) else 0L
        if (hasDur && diff > MAX_DURATION_DIFF_MS) return null
        if (localArtist.isBlank()) {
            // Without an artist, only a tight title + length match is trusted.
            if (!hasDur || diff > MAX_DURATION_DIFF_NO_ARTIST_MS || titleSim < MIN_TITLE_SIM_NO_ARTIST) return null
        }
        val durScore = if (hasDur) 1.0 - diff.toDouble() / MAX_DURATION_DIFF_MS else 0.5
        return 0.55 * titleSim + 0.25 * artistSim + 0.20 * durScore
    }

    private fun findCatalogArt(title: String, artist: String, durationMs: Long): String? {
        val cleanTitle = cleanLocalTitle(title)
        if (cleanTitle.length < 3) return null
        val query = "$cleanTitle ${cleanLocalTitle(artist)}".trim()
        val candidates = queryItunes(query)
        return candidates
            .filter { scoreCandidate(it, cleanTitle, cleanLocalTitle(artist), durationMs) != null }
            .firstOrNull { !it.artUrl.isNullOrBlank() }
            ?.artUrl
    }

    private fun readEmbedded(context: Context, song: Song): EmbeddedRead {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(song.uri))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
            val picture = retriever.embeddedPicture

            val badTitles = listOf("unknown", "download", "original artist", "various", "track", "downlo")
            val titleOk = !title.isNullOrBlank() && badTitles.none { title.equals(it, ignoreCase = true) } && !title.contains(".mp3")
            val artistOk = !artist.isNullOrBlank() && !MatchUtils.isUnknownArtist(artist)
            val tags = if (titleOk && artistOk) {
                FingerprintResult(
                    title = title!!,
                    artist = artist!!,
                    album = album?.takeIf { it.isNotBlank() } ?: "Single",
                    albumArtUrl = null,
                    matchConfidence = 0.90f
                )
            } else null
            EmbeddedRead(tags, picture)
        } catch (_: Exception) {
            EmbeddedRead(null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryItunes(query: String): List<CatalogCandidate> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = (URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=25&country=IN")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != 200) {
                noteFailure("HTTP $code from iTunes")
                conn.disconnect()
                return emptyList()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val item = results.getJSONObject(i)
                val title = item.optString("trackName", "").trim()
                if (title.isBlank()) return@mapNotNull null
                CatalogCandidate(
                    title = title,
                    artist = item.optString("artistName", "").trim(),
                    album = item.optString("collectionName", "Single").trim().ifBlank { "Single" },
                    durationMs = item.optLong("trackTimeMillis", 0L),
                    artUrl = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            noteFailure("${e.javaClass.simpleName} from iTunes: ${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ artwork

    /**
     * Artwork files get a new name every time, so the UI never keeps showing a stale cover.
     * Older covers for the same song are removed.
     */
    private fun coverFile(context: Context, songId: Long): File {
        context.filesDir.listFiles { f -> f.name.startsWith("cover_${songId}_") || f.name == "cover_$songId.jpg" }
            ?.forEach { runCatching { it.delete() } }
        return File(context.filesDir, "cover_${songId}_${System.currentTimeMillis()}.jpg")
    }

    private fun saveArtBytes(context: Context, songId: Long, bytes: ByteArray): String? {
        if (bytes.size <= 512) return null
        return runCatching {
            val dest = coverFile(context, songId)
            FileOutputStream(dest).use { it.write(bytes) }
            Uri.fromFile(dest).toString()
        }.getOrNull()
    }

    private fun saveArtUrl(context: Context, songId: Long, urlString: String): String? {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@runCatching null
            }
            val dest = coverFile(context, songId)
            conn.inputStream.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
            conn.disconnect()
            if (dest.exists() && dest.length() > 512) Uri.fromFile(dest).toString() else null
        }.getOrNull()
    }

    // ------------------------------------------------------------------ batch

    /**
     * Re-checks the songs it is given. [onSongFixed] applies a verified match. [onSongUnmatched]
     * is called when no verified match exists, so an earlier wrong fix can be reverted.
     */
    suspend fun batchFixAllUnknown(
        context: Context,
        songs: List<Song>,
        onProgress: (current: Int, total: Int, songName: String) -> Unit,
        onSongFixed: (Song, FingerprintResult) -> Unit,
        onSongUnmatched: (Song) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val total = songs.size
        if (total == 0) return@withContext 0
        networkFailures.set(0)
        firstFailure = null

        val fixedCount = AtomicInteger(0)
        val done = AtomicInteger(0)
        val semaphore = Semaphore(3)

        coroutineScope {
            songs.map { song ->
                async {
                    semaphore.withPermit {
                        try {
                            val result = autoFixSong(context, song)
                            // Only a verified match counts. The cleaned-title fallback (0.55) is not a fix.
                            if (result.matchConfidence >= 0.60f) {
                                withContext(Dispatchers.Main) { onSongFixed(song, result) }
                                fixedCount.incrementAndGet()
                            } else {
                                withContext(Dispatchers.Main) { onSongUnmatched(song) }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        val current = done.incrementAndGet()
                        withContext(Dispatchers.Main) { onProgress(current, total, song.title) }
                    }
                }
            }.awaitAll()
        }
        lastRunNetworkFailures = networkFailures.get()
        lastRunFirstError = firstFailure
        fixedCount.get()
    }
}
