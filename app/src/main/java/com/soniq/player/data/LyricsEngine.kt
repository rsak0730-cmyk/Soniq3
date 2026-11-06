package com.soniq.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<TimedWord> = emptyList(),
    /** When this line stops being the active line (exclusive). */
    val endMs: Long = Long.MAX_VALUE,
    /** True only when the source supplied an actual timestamp for each word. */
    val wordSynced: Boolean = false
)

data class LyricsResult(
    val lines: List<LyricLine>,
    /** True only when the lyrics carry real timestamps. Plain lyrics are never time-faked. */
    val synced: Boolean,
    /** "Hindi", "Hinglish" or "English/Other". */
    val language: String,
    /** Where the lyrics came from, e.g. "LRCLIB" or "Local .lrc file". */
    val source: String,
    /** The catalog title and artist the lyrics were matched to, so a wrong match is visible. */
    val matchLabel: String = ""
) {
    companion object {
        val NONE = LyricsResult(emptyList(), false, "", "")
    }
}

object LyricsEngine {

    private const val USER_AGENT = "SONIQ-Player/1.0 (Android)"
    private const val CACHE_DIR = "lyrics_cache"
    private const val MISS_RETRY_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_LINE_MS = 9000L
    private const val MIN_TITLE_SIM = 0.72
    private const val MIN_ARTIST_SIM = 0.45
    private const val MAX_DURATION_DIFF_SEC = 3.0

    private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val HINGLISH_MARKERS = Regex(
        """\b(tera|teri|tere|mera|meri|mere|mein|main|tu|tum|tujhe|mujhe|hai|hain|kya|nahi|nahin|ke|ki|ka|""" +
            """ko|se|pyar|pyaar|dil|zindagi|mohabbat|yaad|raat|jaane|jaana|saath|kaise|kyun|sanam|ishq|rab|""" +
            """jaan|aaja|aao|hum|tumhara|tumhari|bina|lagi|chhod|dekho|bolo|phir|kab|jab|tab|kuch|koi|sab|""" +
            """sach|naa|mann|dard|waqt|ho|tenu|mainu|menu|tainu)\b""",
        RegexOption.IGNORE_CASE
    )

    private data class RemoteLyrics(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val durationSec: Double,
        val synced: String?,
        val plain: String?
    )

    /**
     * Returns the best lyrics for a song. Order: a local .lrc next to the file, then the
     * identity cache, then LRCLIB. Hindi/Hinglish versions are preferred; other languages
     * are used when no Hindi/Hinglish version exists.
     */
    suspend fun loadLyricsForSong(context: Context, song: Song): LyricsResult = withContext(Dispatchers.IO) {
        loadLocalSidecar(song)?.let { return@withContext it }

        val key = identityKey(song)
        readCache(context, key)?.let { return@withContext it }

        val online = fetchOnline(song)
        writeCache(context, key, online)
        online ?: LyricsResult.NONE
    }

    // ------------------------------------------------------------------ local

    private fun loadLocalSidecar(song: Song): LyricsResult? {
        val path = song.path ?: return null
        val lrc = File(path.substringBeforeLast('.', path) + ".lrc")
        if (!lrc.exists() || !lrc.canRead()) return null
        val text = runCatching { lrc.readText() }.getOrNull() ?: return null
        val lines = parseSynced(text)
        if (lines.isEmpty()) return null
        return LyricsResult(
            lines = finalizeLines(lines, song.durationMs),
            synced = true,
            language = detectLanguage(text),
            source = "Local .lrc file"
        )
    }

    // ------------------------------------------------------------------ online

    private fun fetchOnline(song: Song): LyricsResult? {
        val title = MatchUtils.cleanTitle(song.title)
        val artist = if (MatchUtils.isUnknownArtist(song.artist)) "" else MatchUtils.cleanTitle(song.artist)
        if (title.length < 2) return null
        val durSec = if (song.durationMs > 0) song.durationMs / 1000.0 else -1.0

        val pool = LinkedHashMap<Long, RemoteLyrics>()

        // 1. Exact lookup with the real duration: the most reliable match when it exists.
        if (artist.isNotBlank()) {
            lrcGet(title, artist, durSec)?.let { pool[it.id] = it }
        }
        // 2. Search queries. All are collected so a Hinglish/Hindi version can beat an English one.
        for (q in buildQueries(title, artist)) {
            for (c in lrcSearch(q)) pool.putIfAbsent(c.id, c)
        }

        val best = pickBest(pool.values.toList(), title, artist, durSec) ?: return null
        val (remote, _) = best
        val synced = !remote.synced.isNullOrBlank()
        val body = if (synced) remote.synced!! else remote.plain!!
        val lines = if (synced) finalizeLines(parseSynced(body), song.durationMs) else plainLines(body)
        if (lines.isEmpty()) return null
        return LyricsResult(
            lines = lines,
            synced = synced,
            language = detectLanguage(body),
            source = "LRCLIB",
            matchLabel = "${remote.trackName} — ${remote.artistName}"
        )
    }

    private fun pickBest(
        list: List<RemoteLyrics>,
        title: String,
        artist: String,
        durSec: Double
    ): Pair<RemoteLyrics, Double>? {
        var best: Pair<RemoteLyrics, Double>? = null
        for (c in list) {
            val body = c.synced?.takeIf { it.isNotBlank() } ?: c.plain?.takeIf { it.isNotBlank() } ?: continue
            val titleSim = MatchUtils.similarity(title, c.trackName)
            if (titleSim < MIN_TITLE_SIM) continue
            val artistSim = if (artist.isBlank()) 0.5 else MatchUtils.artistSimilarity(artist, c.artistName)
            if (artist.isNotBlank() && artistSim < MIN_ARTIST_SIM) continue

            val hasDur = durSec > 0 && c.durationSec > 0
            val durDiff = if (hasDur) abs(durSec - c.durationSec) else 0.0
            if (hasDur && durDiff > MAX_DURATION_DIFF_SEC) continue
            if (artist.isBlank() && !hasDur) continue // never match an unknown artist on title alone
            val durScore = if (hasDur) 1.0 - durDiff / MAX_DURATION_DIFF_SEC else 0.5

            val synced = !c.synced.isNullOrBlank()
            val langRank = if (detectLanguage(body) != "English/Other") 1.0 else 0.0
            val score = 0.35 * titleSim +
                0.25 * artistSim +
                0.15 * durScore +
                (if (synced) 0.6 else 0.0) +   // timed lyrics always beat untimed ones
                0.25 * langRank                // then Hindi/Hinglish beats other languages

            if (best == null || score > best.second) best = c to score
        }
        return best
    }

    private fun buildQueries(title: String, artist: String): List<String> {
        val queries = LinkedHashSet<String>()
        if (artist.isNotBlank()) queries.add("$title $artist")
        queries.add(title)
        title.split(Regex("""\s+[-|/]\s+|\s+x\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim()
            ?.takeIf { it.length >= 3 }
            ?.let { queries.add(it) }
        return queries.take(4)
    }

    private fun lrcGet(title: String, artist: String, durSec: Double): RemoteLyrics? {
        val params = buildString {
            append("track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (durSec > 0) append("&duration=").append(durSec.roundToInt())
        }
        val body = getText("https://lrclib.net/api/get?$params") ?: return null
        return runCatching { toRemote(JSONObject(body)) }.getOrNull()
    }

    private fun lrcSearch(query: String): List<RemoteLyrics> {
        val body = getText("https://lrclib.net/api/search?q=${enc(query)}") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i -> toRemote(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private fun toRemote(o: JSONObject): RemoteLyrics? {
        if (o.optBoolean("instrumental", false)) return null
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        return RemoteLyrics(
            id = o.optLong("id", o.toString().hashCode().toLong()),
            trackName = o.optString("trackName", o.optString("name", "")),
            artistName = o.optString("artistName", ""),
            durationSec = o.optDouble("duration", -1.0),
            synced = synced,
            plain = plain
        )
    }

    private fun getText(urlString: String): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ parsing

    /** Parses LRC text, including enhanced per-word tags like <00:12.34>word. */
    private fun parseSynced(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (rawLine in text.lineSequence()) {
            val times = LRC_TIME.findAll(rawLine).toList()
            if (times.isEmpty()) continue
            val body = rawLine.substring(times.last().range.last + 1)
            if (body.isBlank()) continue
            for (tm in times) {
                val start = tagMs(tm)
                val enhanced = if (WORD_TAG.containsMatchIn(body)) parseEnhancedWords(body) else null
                val plainText = enhanced?.first ?: body.trim()
                val words = enhanced?.second ?: emptyList()
                if (plainText.isNotBlank()) {
                    out += LyricLine(start, plainText, words, wordSynced = enhanced != null)
                }
            }
        }
        return out.sortedBy { it.timestampMs }
    }

    /**
     * Enhanced LRC tags timestamp each following word/segment. Accept it as true
     * word-sync only when every tagged segment contains exactly one token. If a
     * segment contains multiple words, fall back to line-sync rather than inventing
     * per-word timings.
     */
    private fun parseEnhancedWords(body: String): Pair<String, List<TimedWord>>? {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return null
        val words = ArrayList<TimedWord>()
        val plain = StringBuilder()
        for ((i, tag) in tags.withIndex()) {
            val start = tagMs(tag)
            val textFrom = tag.range.last + 1
            val textTo = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val segment = body.substring(textFrom, textTo).trim()
            if (segment.isBlank()) continue
            // A timestamp cannot be split accurately across multiple words.
            if (segment.any { it.isWhitespace() }) return null
            val end = if (i + 1 < tags.size) tagMs(tags[i + 1]) else Long.MAX_VALUE
            if (end <= start) return null
            words += TimedWord(segment, start, end)
            if (plain.isNotEmpty()) plain.append(' ')
            plain.append(segment)
        }
        return if (words.isEmpty()) null else plain.toString() to words
    }

    private fun tagMs(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLongOrNull() ?: 0L
        val seconds = m.groupValues[2].toLongOrNull() ?: 0L
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> (frac.toLongOrNull() ?: 0L) * 100
            2 -> (frac.toLongOrNull() ?: 0L) * 10
            else -> frac.toLongOrNull() ?: 0L
        }
        return (minutes * 60 + seconds) * 1000 + ms
    }

    private fun plainLines(text: String): List<LyricLine> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { LyricLine(-1L, it) }

    /**
     * Fills in line end times. Word timings are retained only when the lyric source
     * supplied genuine per-word timestamps; line-level LRC is never subdivided by guesswork.
     */
    private fun finalizeLines(raw: List<LyricLine>, durationMs: Long): List<LyricLine> {
        val sorted = raw.sortedBy { it.timestampMs }
        val out = ArrayList<LyricLine>(sorted.size)
        for (i in sorted.indices) {
            val line = sorted[i]
            val next = sorted.getOrNull(i + 1)?.timestampMs
            var end = if (next != null && next > line.timestampMs) {
                min(next, line.timestampMs + MAX_LINE_MS)
            } else {
                line.timestampMs + 6000L
            }
            if (durationMs > 0 && end > durationMs) end = durationMs
            if (end <= line.timestampMs) end = line.timestampMs + 1500L
            val words = if (line.wordSynced && line.words.isNotEmpty()) {
                line.words.mapIndexed { wordIndex, word ->
                    if (wordIndex == line.words.lastIndex || word.endMs == Long.MAX_VALUE) {
                        word.copy(endMs = end)
                    } else word
                }
            } else emptyList()
            out += line.copy(words = words, endMs = end, wordSynced = line.wordSynced && words.isNotEmpty())
        }
        return out
    }


    /** Hindi (Devanagari) or Hinglish (romanized Hindi) versus everything else. */
    fun detectLanguage(text: String): String {
        val devanagari = text.count { it in 'ऀ'..'ॿ' }
        if (devanagari >= 15) return "Hindi"
        val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }.coerceAtLeast(1)
        val markers = HINGLISH_MARKERS.findAll(text).count()
        return if (markers >= 4 && markers.toDouble() / words >= 0.08) "Hinglish" else "English/Other"
    }

    // ------------------------------------------------------------------ cache

    /** Cache key is the song identity (artist + title + length), not the MediaStore id. */
    private fun identityKey(song: Song): String {
        val raw = "${MatchUtils.cleanTitle(song.artist).lowercase()}|" +
            "${MatchUtils.cleanTitle(song.title).lowercase()}|${song.durationMs / 1000}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCache(context: Context, key: String): LyricsResult? {
        val file = File(File(context.filesDir, CACHE_DIR), "$key.json")
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            if (obj.optInt("wordTimingVersion", 0) != 2) return null
            if (obj.optBoolean("miss", false)) {
                val fresh = System.currentTimeMillis() - obj.optLong("ts") < MISS_RETRY_MS
                return if (fresh) LyricsResult.NONE else null
            }
            val arr = obj.getJSONArray("lines")
            val lines = (0 until arr.length()).map { i ->
                val lo = arr.getJSONObject(i)
                val words = lo.optJSONArray("w")?.let { wa ->
                    (0 until wa.length()).map { j ->
                        val w = wa.getJSONArray(j)
                        TimedWord(w.getString(2), w.getLong(0), w.getLong(1))
                    }
                } ?: emptyList()
                LyricLine(
                    timestampMs = lo.getLong("t"),
                    text = lo.getString("x"),
                    words = words,
                    endMs = lo.optLong("e", Long.MAX_VALUE),
                    wordSynced = lo.optBoolean("wordSynced", false)
                )
            }
            LyricsResult(
                lines = lines,
                synced = obj.optBoolean("synced"),
                language = obj.optString("language"),
                source = obj.optString("source"),
                matchLabel = obj.optString("match")
            )
        }.getOrNull()
    }

    private fun writeCache(context: Context, key: String, result: LyricsResult?) {
        runCatching {
            val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val obj = JSONObject().put("ts", System.currentTimeMillis()).put("wordTimingVersion", 2)
            if (result == null || result.lines.isEmpty()) {
                obj.put("miss", true)
            } else {
                obj.put("synced", result.synced)
                obj.put("language", result.language)
                obj.put("source", result.source)
                obj.put("match", result.matchLabel)
                val arr = JSONArray()
                for (line in result.lines) {
                    val lo = JSONObject().put("t", line.timestampMs).put("x", line.text)
                    .put("e", line.endMs).put("wordSynced", line.wordSynced)
                    if (line.words.isNotEmpty()) {
                        val wa = JSONArray()
                        for (w in line.words) wa.put(JSONArray().put(w.startMs).put(w.endMs).put(w.text))
                        lo.put("w", wa)
                    }
                    arr.put(lo)
                }
                obj.put("lines", arr)
            }
            File(dir, "$key.json").writeText(obj.toString())
        }
    }
}
