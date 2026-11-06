package com.soniq.player.data

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String?,
    val folder: String,
    val dateAdded: Long,
    /** Absolute file path when Android exposes it. Used to find a sidecar .lrc file. */
    val path: String? = null
)
