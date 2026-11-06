# SONIQ

SONIQ is a Kotlin + Jetpack Compose local music player starter project for Android 10+.

## Included
- Local audio indexing through Android MediaStore
- Song, artist, album, folder, favorites, recent and playlist views
- Search across title, artist, album and folder, plus recent search chips
- Album artwork thumbnails where Android exposes artwork
- A–Z quick-jump rail with animated/bubbly tap feedback
- Media3 ExoPlayer + MediaSessionService for background playback and system media controls
- Full player, mini player, queue next/previous, seek, shuffle and repeat
- Theme: liquid-glass blue navigation and search bar; soft neumorphic mint interface and player
- Word-by-word lyrics: the current word glows neon blue, timed to the real line timestamps (LRCLIB)
- Hindi / Hinglish lyrics are preferred; other languages are used when no Hindi/Hinglish version exists
- Auto-Fix verifies title, artist and song length before applying a catalog match, and fetches artwork
- Device output endpoint detection for phone speaker, wired, Bluetooth and USB
- Native Android Equalizer, BassBoost and Virtualizer controls with device-reported band count
- Sleep timer UI with preset and custom duration
- GitHub Actions debug APK workflow

## Build
Open this folder in Android Studio (JDK 17, Android SDK 36), or push to GitHub and use the Actions workflow. The workflow uploads `app-debug.apk` as an artifact.

## Important limitations
- The sleep timer countdown currently runs in the app process. Android may stop it if the app is force-stopped or aggressively killed; it is not a guaranteed alarm.
- Output detection reports Android-visible output endpoints. Android chooses the active route; this app does not force Bluetooth/wired routing.
- Equalizer bands/effects depend on the phone's native Android audio effects. This is not a custom software 10/20/31-band DSP.
- Audio visualizer is a decorative animated UI visualizer, not a captured waveform from the audio stream.
- Lyrics come from LRCLIB and are only as complete as that database. Plain (untimed) lyrics are shown without a moving highlight; timing is never invented.
- Word-level timing is estimated inside each line unless the lyrics file carries per-word tags (enhanced LRC).
- No cloud streaming is included.
- This source has not been compiled or device-tested in this environment. Build in Android Studio/GitHub Actions and fix any SDK/vendor-specific issues revealed by the compiler/device.

## Permissions
SONIQ requests local audio read access and notification permission on Android 13+. Playback itself uses Android Media3 media session APIs.
