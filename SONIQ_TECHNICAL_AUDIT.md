# SONIQ technical reliability audit — source changes

This audit is based on the uploaded `SONIQ_v0.6_fixed` project.

## Changes made in this patch

### Catalog Auto-Fix
- Serialized iTunes catalog requests instead of launching three concurrent song workers.
- Added a 1.2-second minimum interval between catalog requests.
- Reduced catalog searches to at most two focused queries per song.
- Removed the extra iTunes search that was being made solely to fetch artwork for tracks with valid embedded tags.
- Added a seven-day on-device cache for successful catalog responses, including empty search results.
- Added retries with exponential backoff for HTTP 429 and server-side 5xx responses; honors `Retry-After` when supplied.
- Reports one final failure per failed catalog query rather than counting each retry as a separate failed song.
- Preserves cancellation instead of swallowing coroutine cancellation exceptions.
- Tightened title and artist similarity thresholds to reduce weak matches.
- Changed the manual match label so embedded/catalog metadata confidence is not presented as a guaranteed verification.

### Lyrics
- Tightened LRCLIB title and artist identity thresholds to reduce alternate-recording lyric matches.
- Rejects lyric line timestamps that fall outside the local track duration.
- Bumped the lyrics cache schema so old cached lyric selections are fetched again.
- Keeps word highlighting only for genuine enhanced-LRC word timestamps. Ordinary line LRC is not split into invented word timings.

### Playback and Android media controls
- When Auto-Fix corrects the currently playing song, updates its MediaItem metadata and restores the playback position so Android's MediaSession/lock-screen metadata can refresh.
- Improved playback recovery when the final queue item errors.
- Corrected transient audio-focus handling so playback resumes only if it was playing before the interruption; permanent focus loss does not auto-resume.

### Documentation
- Corrected README statements that previously implied estimated word-level timing and acoustic fingerprint support that were not actually present in this source.

## Important capabilities still not implemented

- This source still does **not** generate acoustic fingerprints with Chromaprint or query AcoustID. Current Auto-Fix uses embedded tags and iTunes catalog metadata matching. A real fingerprint engine requires adding and validating a compatible fingerprint library, AcoustID API integration/key handling, and recording-level result mapping.
- The current batch UI operation is still app-process coroutine work. It is not yet a persistent WorkManager job, so force-stop/process death can interrupt it. Persistent pause/resume and a separate background second-pass engine remain future work.
- iTunes can still rate-limit or omit tracks. The patch reduces request pressure but cannot guarantee catalog coverage or correct identity for every local file.
- Word-level synchronization cannot be guaranteed when the lyric source only supplies line timestamps or has inaccurate enhanced-LRC timestamps. Correct timing needs accurate word timestamps or audio-based alignment.
- Updating the currently playing MediaItem metadata may cause Media3 to reprepare the current item on some versions/devices. Playback position is restored in code, but this needs real-device validation.

## Validation performed

- ZIP structure inspected before editing.
- Kotlin source delimiter counts checked for edited Kotlin files.
- AndroidManifest.xml parsed successfully.
- A small standalone Kotlin check validated the nullable retry-delay smart-cast and inline `runCatching` return pattern.
- A full Android Gradle build and device tests were **not** run: this uploaded project has no Gradle wrapper and no Gradle executable is installed in the editing environment.

## Files modified

- `app/src/main/java/com/soniq/player/audio/AudioFingerprintFixer.kt`
- `app/src/main/java/com/soniq/player/data/LyricsEngine.kt`
- `app/src/main/java/com/soniq/player/playback/PlaybackService.kt`
- `app/src/main/java/com/soniq/player/ui/SoniqApp.kt`
- `README.md`
