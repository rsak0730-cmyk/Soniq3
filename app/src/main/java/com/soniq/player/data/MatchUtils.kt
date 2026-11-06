package com.soniq.player.data

import kotlin.math.max

/**
 * Shared string matching used by the auto-fixer and the lyrics search.
 * Keeps Devanagari (Hindi) letters and vowel marks, which the old cleaners
 * stripped out and so turned Hindi titles into empty strings.
 */
object MatchUtils {

    private val NOISE = setOf(
        "official", "video", "audio", "lyric", "lyrics", "hd", "hq", "4k", "full", "song", "songs",
        "mp3", "remix", "feat", "ft", "vevo", "download", "pagalworld", "djpunjab", "mrjatt", "ytmp3"
    )

    private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val ARTIST_SPLIT = Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""\[[^\]]*]|\([^)]*\)""")
    private val SPAM_WORDS = Regex(
        """(?i)\b(official|video|audio|lyrics?|lyric video|full song|hd|4k|hq|mp3|320kbps|128kbps|""" +
            """download|remix|mashup|slowed|reverb|lofi|ringtone|pagalworld|mr-jatt|djpunjab|ytmp3|vevo)\b"""
    )
    private val EXTENSION = Regex("""(?i)\.(mp3|m4a|flac|wav|aac|ogg)$""")
    private val LEADING_NUMBER = Regex("""^\s*\d{1,3}[\s._-]+""")

    fun tokens(text: String): Set<String> = text.lowercase()
        .replace(NON_WORD, " ")
        .split(' ')
        .filter { it.length >= 2 && it !in NOISE }
        .toSet()

    /**
     * Similarity between 0.0 and 1.0. A word counts as matched only when a word on the other
     * side is nearly identical (edit ratio >= 0.8), so different songs that share one word
     * do not score as a match. Joined-text comparison only catches run-together spellings.
     */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a).toList()
        val tb = tokens(b).toList()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val matchedA = ta.count { x -> tb.any { y -> tokenRatio(x, y) >= 0.8 } }
        val matchedB = tb.count { y -> ta.any { x -> tokenRatio(x, y) >= 0.8 } }
        val wordScore = (matchedA + matchedB).toDouble() / (ta.size + tb.size)
        val joinedA = ta.joinToString("")
        val joinedB = tb.joinToString("")
        val joinedScore = 1.0 - levenshtein(joinedA, joinedB).toDouble() / max(joinedA.length, joinedB.length).coerceAtLeast(1)
        return max(wordScore, if (joinedScore >= 0.85) joinedScore else 0.0)
    }

    private fun tokenRatio(x: String, y: String): Double {
        if (x == y) return 1.0
        val longest = max(x.length, y.length).coerceAtLeast(1)
        return 1.0 - levenshtein(x, y).toDouble() / longest
    }

    /** Artist similarity that understands "A, B", "A & B", "feat." and "x" splits. */
    fun artistSimilarity(local: String, remote: String): Double {
        val localParts = listOf(local) + local.split(ARTIST_SPLIT)
        val remoteParts = listOf(remote) + remote.split(ARTIST_SPLIT)
        var best = 0.0
        for (l in localParts) {
            for (r in remoteParts) {
                best = max(best, similarity(l, r))
            }
        }
        return best
    }

    /** Title cleanup for search queries: strips bracketed noise, file extensions and track numbers. */
    fun cleanTitle(raw: String): String = raw
        .replace('_', ' ')
        .replace(BRACKETS, " ")
        .replace(SPAM_WORDS, " ")
        .replace(EXTENSION, " ")
        .replace(LEADING_NUMBER, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '.')

    /** True when a tag is empty or a placeholder such as "<unknown>". */
    fun isUnknownArtist(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val lower = raw.trim().lowercase()
        return lower == "<unknown>" || lower.contains("unknown") || lower.contains("various") ||
            lower.contains("original artist") || lower.contains("download")
    }

    private fun levenshtein(a: String, b: String): Int {
        val s = a.take(60)
        val t = b.take(60)
        if (s.isEmpty()) return t.length
        if (t.isEmpty()) return s.length
        var prev = IntArray(t.length + 1) { it }
        var cur = IntArray(t.length + 1)
        for (i in 1..s.length) {
            cur[0] = i
            for (j in 1..t.length) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[t.length]
    }
}
