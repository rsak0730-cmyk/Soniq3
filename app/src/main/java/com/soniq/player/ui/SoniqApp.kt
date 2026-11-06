@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.common.util.concurrent.MoreExecutors
import com.soniq.player.audio.AudioDeviceMonitor
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.AudioFingerprintFixer
import com.soniq.player.audio.AudioOutput
import com.soniq.player.audio.GyroscopeParallaxEngine
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.data.*
import com.soniq.player.playback.PlaybackService
import com.soniq.player.ui.theme.*
import com.soniq.player.data.LyricsResult
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.*

// Palette aliases. The actual colors live in ui/theme/SoniqPalette.kt.
private val OLEDBlack = NeoBg
private val NeonPurple = Color(0xFF4B6FD6)
private val DeepViolet = NeoAccent
private val ElectricCyan = NeoAccent
private val AquaGlow = NeoAccentSoft
private val MutedLavender = NeoMuted
private val GlassPill = NeoSurface
private val GlassBorder = NeoEdge

private val DeepOceanAbyss = NeoBg
private val OceanNavyDeep = Color(0xFFB4CCC3)
private val BioluminescentCyan = NeoAccent
private val OceanMistGlass = Color(0x80FFFFFF)
private val OceanGlassBorder = NeoEdge

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    EQUALIZER("Equalizer", Icons.Default.Equalizer),
    PLAYLISTS("Playlists", Icons.Default.QueueMusic),
    SETTINGS("Settings", Icons.Default.Settings)
}

private fun applySavedMetadataOverrides(rawSongs: List<Song>, prefs: SharedPreferences): List<Song> {
    val overridesJson = prefs.getString("song_overrides", null) ?: return rawSongs
    return runCatching {
        val root = JSONObject(overridesJson)
        rawSongs.map { song ->
            val key = song.id.toString()
            if (root.has(key)) {
                val obj = root.getJSONObject(key)
                song.copy(
                    title = obj.optString("title", song.title),
                    artist = obj.optString("artist", song.artist),
                    album = obj.optString("album", song.album),
                    albumArtUri = obj.optString("art", "").ifBlank { song.albumArtUri }
                )
            } else song
        }
    }.getOrDefault(rawSongs)
}

private fun saveMetadataOverride(prefs: SharedPreferences, songId: Long, title: String, artist: String, album: String, artUri: String?) {
    runCatching {
        val raw = prefs.getString("song_overrides", "{}") ?: "{}"
        val root = JSONObject(raw)
        val entry = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            if (!artUri.isNullOrBlank()) put("art", artUri)
        }
        root.put(songId.toString(), entry)
        prefs.edit().putString("song_overrides", root.toString()).apply()
    }
}

private fun removeMetadataOverride(prefs: SharedPreferences, songId: Long) {
    runCatching {
        val root = JSONObject(prefs.getString("song_overrides", "{}") ?: "{}")
        root.remove(songId.toString())
        prefs.edit().putString("song_overrides", root.toString()).apply()
    }
}

/** Tracks whose title/artist came from the auto-fixer. A manual edit clears the flag. */
private fun markAutoFixed(prefs: SharedPreferences, songId: Long, add: Boolean) {
    val current = prefs.getStringSet("auto_fixed_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
    if (add) current.add(songId.toString()) else current.remove(songId.toString())
    prefs.edit().putStringSet("auto_fixed_ids", current).apply()
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun BouncyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "bouncy_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun BouncyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    content: @Composable () -> Unit
) {
    BouncyButton(onClick = onClick, modifier = modifier.size(size)) {
        content()
    }
}

@Composable
fun SongThumb(song: Song, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val a = song.albumArtUri.orEmpty()
    val isFileArt = a.startsWith("file://")
    val isRemoteArt = a.startsWith("http://") || a.startsWith("https://")
    val isMediaStoreArt = a.startsWith("content://")

    // A re-fixed cover is a new file, and its timestamp changes the cache key, so the new image shows at once.
    val fileReady = remember(a) {
        isFileArt && runCatching {
            val f = File(Uri.parse(a).path.orEmpty())
            f.exists() && f.length() > 512
        }.getOrDefault(false)
    }
    val artVersion = remember(a) {
        if (isFileArt) runCatching { File(Uri.parse(a).path.orEmpty()).lastModified() }.getOrDefault(0L) else 0L
    }

    // Fallback 1: the picture embedded in the audio file's own tags.
    var embedded by remember(song.id, song.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(song.id, song.uri, fileReady, isRemoteArt) {
        if (!fileReady && !isRemoteArt) {
            embedded = withContext(Dispatchers.IO) {
                MusicRepository.extractEmbeddedPicture(context, song.uri)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }
    }
    val embeddedBitmap = embedded

    val request: ImageRequest? = if (fileReady || isRemoteArt || isMediaStoreArt) {
        ImageRequest.Builder(context)
            .data(a)
            .memoryCacheKey("$a#$artVersion")
            .crossfade(true)
            .build()
    } else null

    // Order: a fixed cover (file/web) first, then embedded art, then the MediaStore album art.
    // The placeholder icon sits underneath, so a failed load still shows a clean tile.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.radialGradient(listOf(NeoInset, NeoBg)))
            .border(1.dp, NeoEdge, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, null, tint = NeoAccent.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
        when {
            request != null && (fileReady || isRemoteArt || embeddedBitmap == null) -> AsyncImage(
                model = request,
                contentDescription = "Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            embeddedBitmap != null -> Image(
                bitmap = embeddedBitmap,
                contentDescription = "Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Which song is playing right now, shared by every list so each row can show the live indicator. */
object NowPlayingState {
    var songId by mutableStateOf<Long?>(null)
    var playing by mutableStateOf(false)
}

/** Animated neon border and glow for the row that is currently playing. */
@Composable
fun Modifier.nowPlayingGlow(): Modifier {
    val t by rememberInfiniteTransition(label = "now_glow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "now_sweep"
    )
    val shape = RoundedCornerShape(20.dp)
    return this
        .shadow(10.dp, shape, ambientColor = NeonGlow, spotColor = NeonGlow)
        .border(
            width = 2.dp,
            brush = Brush.linearGradient(
                colors = listOf(NeonBlue, GlassBlueA, NeonBlue),
                start = Offset(t * 700f - 350f, 0f),
                end = Offset(t * 700f + 350f, 700f)
            ),
            shape = shape
        )
}

/** Three bouncing bars drawn over the thumbnail of the playing song. */
@Composable
fun NowPlayingBars(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "eq_bars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "eq_t"
    )
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99102A3A))) {
        val barW = size.width * 0.14f
        val gap = size.width * 0.08f
        val startX = (size.width - (3 * barW + 2 * gap)) / 2f
        for (i in 0 until 3) {
            val phase = (t + i * 0.33f) % 1f
            val wave = kotlin.math.sin(phase.toDouble() * kotlin.math.PI).toFloat()
            val h = size.height * (0.30f + 0.45f * kotlin.math.abs(wave))
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(NeonBlue, GlassBlueA)),
                topLeft = Offset(startX + i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
fun SongCard(
    song: Song,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToAlbum: () -> Unit,
    onEdit: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isNow = NowPlayingState.songId == song.id
    val playingNow = isNow && NowPlayingState.playing
    BouncyButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .neuRaised(cornerRadius = 20.dp, depth = 6.dp)
                .then(if (isNow) Modifier.nowPlayingGlow() else Modifier)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                SongThumb(song, Modifier.fillMaxSize())
                if (playingNow) NowPlayingBars(Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = if (isNow) NeonBlue else NeoInk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${song.artist} • ${song.album}",
                    color = MutedLavender,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${song.folder} • ${timeText(song.durationMs)}",
                    color = NeoAccent.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (favorite) NeoDanger else MutedLavender,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.AutoFixHigh, "Auto-Fix", tint = NeoAccent, modifier = Modifier.size(19.dp))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = MutedLavender, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(NeoDialog)
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist", color = NeoInk) },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = NeoAccent) },
                        onClick = { menuExpanded = false; onAddToPlaylist() }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Custom Album", color = NeoInk) },
                        leadingIcon = { Icon(Icons.Default.Album, null, tint = NeonPurple) },
                        onClick = { menuExpanded = false; onAddToAlbum() }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit Metadata / Auto-Fix", color = NeoInk) },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, tint = NeoAccent) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                }
            }
        }
    }
}

@Composable
fun GroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    representativeSong: Song?,
    isCustom: Boolean,
    onClick: () -> Unit
) {
    BouncyButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = NeoSurface, shadowElevation = 6.dp,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, if (isCustom) ElectricCyan.copy(alpha = 0.7f) else GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (representativeSong != null) {
                    SongThumb(song = representativeSong, modifier = Modifier.size(48.dp))
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(NeonPurple.copy(alpha = 0.5f), ElectricCyan.copy(alpha = 0.5f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = NeoInk)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = NeoInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isCustom) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = NeonPurple.copy(alpha = 0.4f)) {
                                Text("CUSTOM", color = ElectricCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(subtitle, color = MutedLavender, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, tint = ElectricCyan)
            }
        }
    }
}

@Composable
fun SamsungAlphabetScroller(
    songs: List<Song>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val alphabet = remember { ('A'..'Z').toList() }
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<Char?>(null) }
    var railHeightPx by remember { mutableFloatStateOf(1f) }

    fun scrollToLetter(letter: Char) {
        activeLetter = letter
        val targetIndex = songs.indexOfFirst { it.title.trim().firstOrNull()?.uppercaseChar() == letter }
        if (targetIndex >= 0) {
            scope.launch { listState.scrollToItem(targetIndex) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .onGloballyPositioned { coordinates -> railHeightPx = coordinates.size.height.toFloat() }
            .pointerInput(alphabet, songs) {
                detectTapGestures(
                    onPress = { offset ->
                        val itemHeight = size.height.toFloat() / alphabet.size.coerceAtLeast(1)
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                        tryAwaitRelease()
                        activeLetter = null
                    }
                )
            }
            .pointerInput(alphabet, songs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val itemHeight = railHeightPx / alphabet.size.coerceAtLeast(1)
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                    },
                    onDragEnd = { activeLetter = null },
                    onDragCancel = { activeLetter = null },
                    onDrag = { change: PointerInputChange, _ ->
                        change.consume()
                        val itemHeight = railHeightPx / alphabet.size.coerceAtLeast(1)
                        val index = (change.position.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                    }
                )
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { letter ->
                val isSelected = activeLetter == letter
                Text(
                    text = letter.toString(),
                    color = if (isSelected) ElectricCyan else MutedLavender.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                )
            }
        }
        activeLetter?.let { letter ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-46).dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ElectricCyan, NeonPurple)))
                    .border(1.5.dp, NeoInk, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = letter.toString(), color = OLEDBlack, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun FloatingMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    primaryColor: Color,
    secondaryColor: Color,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .neuRaised(cornerRadius = 24.dp, depth = 7.dp)
            .clip(shape)
            .clickable { onOpen() }
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = secondaryColor,
            trackColor = NeoInset
        )
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongThumb(song = song, modifier = Modifier.size(46.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = NeoInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    color = MutedLavender,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .neuRaised(cornerRadius = 20.dp, depth = 4.dp)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = secondaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = NeoInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun FloatingBottomNavBar(
    currentTab: MainTab,
    activeColor: Color,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    // Liquid-glass navigation bar: frosted white, blue gradient edge, blue glow, blue gradient pill for the active tab.
    val barShape = RoundedCornerShape(32.dp)
    val pillShape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(16.dp, barShape, ambientColor = GlassGlow, spotColor = GlassGlow)
            .clip(barShape)
            .background(Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0xB8D6ECFF))))
            .border(1.2.dp, Brush.linearGradient(listOf(GlassBlueA, GlassFrost, GlassBlueB)), barShape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.entries.forEach { tab ->
            val selected = currentTab == tab
            BouncyButton(
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 5.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .then(
                            if (selected) {
                                Modifier
                                    .shadow(12.dp, pillShape, ambientColor = GlassGlow, spotColor = GlassGlow)
                                    .clip(pillShape)
                                    .background(Brush.linearGradient(listOf(GlassBlueA, GlassBlueB, GlassBlueC)))
                            } else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    val tint = if (selected) Color.White else NeoMuted
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(
                        tab.label,
                        color = tint,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidWaterWaveBackground(
    isPlaying: Boolean,
    primaryColor: Color = NeonPurple,
    secondaryColor: Color = ElectricCyan,
    pitch: Float = 0f,
    roll: Float = 0f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "water_caustics")
    val t1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 6500 else 12000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave1"
    )
    val t2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 8500 else 16000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave2"
    )
    val animPrimary by animateColorAsState(primaryColor, tween(1200), label = "bgPrimary")
    val animSecondary by animateColorAsState(secondaryColor, tween(1200), label = "bgSecondary")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 1800 else 4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bgPulse"
    )
    // Soft mint background with drifting light and colour blobs that shift with the artwork palette.
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(NeoBg)
        val gyroShiftX = roll * 20.dp.toPx()
        val gyroShiftY = pitch * 20.dp.toPx()
        val x1 = w * (0.65f + 0.18f * cos(t1.toDouble()).toFloat()) + gyroShiftX
        val y1 = h * (0.28f + 0.14f * sin(t2.toDouble()).toFloat()) + gyroShiftY
        val r1 = w * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    NeoLight.copy(alpha = if (isPlaying) 0.85f else 0.55f),
                    NeoLight.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(x1, y1),
                radius = r1
            ),
            radius = r1,
            center = Offset(x1, y1)
        )
        val x2 = w * (0.32f + 0.16f * sin((t1 * 1.2f).toDouble()).toFloat()) - gyroShiftX
        val y2 = h * (0.72f + 0.12f * cos((t2 * 0.9f).toDouble()).toFloat()) - gyroShiftY
        val r2 = w * 0.90f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animSecondary.copy(alpha = (if (isPlaying) 0.16f else 0.08f) * glowPulse),
                    NeoAccentSoft.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = Offset(x2, y2),
                radius = r2
            ),
            radius = r2,
            center = Offset(x2, y2)
        )
        val x3 = w * (0.78f + 0.10f * sin((t2 * 1.1f).toDouble()).toFloat())
        val y3 = h * (0.90f - 0.08f * cos((t1 * 0.7f).toDouble()).toFloat())
        val r3 = w * 0.70f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animPrimary.copy(alpha = (if (isPlaying) 0.14f else 0.07f) * glowPulse),
                    Color.Transparent
                ),
                center = Offset(x3, y3),
                radius = r3
            ),
            radius = r3,
            center = Offset(x3, y3)
        )
    }
}

@Composable
fun RoundedRectTimelineCenterpiece(
    song: Song,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    gyroPitch: Float,
    gyroRoll: Float,
    modifier: Modifier = Modifier
) {
    val amplitudes = RealtimeFftMonitor.amplitudes
    val numBars = 22
    val infinite = rememberInfiniteTransition(label = "artwork_fx")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "art_sweep"
    )
    val breathe by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "art_breathe"
    )
    val accent by animateColorAsState(primaryColor, tween(900), label = "art_accent")
    val accent2 by animateColorAsState(secondaryColor, tween(900), label = "art_accent2")

    // Upright rounded card with an animated neon rim. No gyroscope rotation, so it never tilts.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .neuRaised(cornerRadius = 40.dp, depth = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.94f)
                .clip(RoundedCornerShape(36.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(accent, GlassBlueA, accent2, GlassBlueB, accent),
                        start = Offset(sweep * 900f - 450f, 0f),
                        end = Offset(sweep * 900f + 450f, 900f)
                    )
                )
                .padding(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(33.dp))
                    .background(NeoBg)
            ) {
                SongThumb(
                    song = song,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = breathe; scaleY = breathe }
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Glass gloss across the top.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.28f),
                            0.45f to Color.Transparent
                        ),
                        size = Size(w, h * 0.5f)
                    )
                    // Moving light sheen.
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.18f),
                            1f to Color.Transparent,
                            start = Offset(sweep * w * 2f - w, 0f),
                            end = Offset(sweep * w * 2f, h)
                        )
                    )
                    // Dark base for the visualiser.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1.0f to Color(0xD9102A3A)
                        )
                    )
                    val barWidth = 4.dp.toPx()
                    val spacing = (w * 0.78f - numBars * barWidth) / (numBars - 1).coerceAtLeast(1)
                    val startX = w * 0.11f
                    val bottomY = h - 16.dp.toPx()
                    for (i in 0 until numBars) {
                        val amp = if (isPlaying) amplitudes[i % amplitudes.size] else 0.08f
                        val barHeight = (44.dp.toPx() * amp).coerceIn(4.dp.toPx(), 40.dp.toPx())
                        val bx = startX + i * (barWidth + spacing)
                        drawRoundRect(
                            brush = Brush.verticalGradient(listOf(accent, GlassBlueA)),
                            topLeft = Offset(bx, bottomY - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WaveformSeekScrubber(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val infiniteTransition = rememberInfiniteTransition(label = "wave_timeline")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPlaying) 2800 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    val pct = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((pct * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectDragGestures { change: PointerInputChange, _ ->
                    val pct = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((pct * duration).toLong())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h / 2f
            val activeX = w * progress

            drawLine(
                color = NeoInset,
                start = Offset(activeX, midY),
                end = Offset(w, midY),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            if (activeX > 0f) {
                val wavePath = Path()
                val steps = (activeX / 3.dp.toPx()).toInt().coerceAtLeast(10)
                val waveAmplitude = if (isPlaying) 3.5.dp.toPx() else 1.2.dp.toPx()
                val waveFrequency = 0.045

                for (i in 0..steps) {
                    val px = (i.toFloat() / steps) * activeX
                    val taper = sin((i.toFloat() / steps) * PI.toFloat()).coerceIn(0f, 1f)
                    val py = midY + sin((px * waveFrequency + phase).toDouble()).toFloat() * (waveAmplitude * taper)

                    if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                }

                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f * glowPulse),
                            secondaryColor.copy(alpha = 0.55f * glowPulse)
                        )
                    ),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(primaryColor, secondaryColor)
                    ),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                val headWaveY = midY + sin((activeX * waveFrequency + phase).toDouble()).toFloat() * 1.5.dp.toPx()
                val headCenter = Offset(activeX, headWaveY)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = 0.60f * glowPulse),
                            primaryColor.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = headCenter,
                        radius = 16.dp.toPx()
                    ),
                    radius = 16.dp.toPx(),
                    center = headCenter
                )

                drawCircle(
                    color = NeoInk,
                    radius = 5.dp.toPx(),
                    center = headCenter
                )
                drawCircle(
                    color = secondaryColor,
                    radius = 3.dp.toPx(),
                    center = headCenter
                )
            }
        }
    }
}

private const val LINE_GRACE_MS = 1500L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSyncedKaraokeLyrics(
    lyrics: List<LyricLine>,
    isSynced: Boolean,
    positionMs: Long,
    durationMs: Long,
    activeColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // -1 means nothing is being sung: before the first line, after the last line ends,
    // after the track finishes, or when the lyrics have no timing. The highlight never sticks.
    val activeLineIdx = remember(positionMs, durationMs, lyrics, isSynced) {
        when {
            !isSynced || lyrics.isEmpty() -> -1
            durationMs > 0 && positionMs >= durationMs -> -1
            else -> {
                var idx = -1
                for (i in lyrics.indices) {
                    if (lyrics[i].timestampMs <= positionMs) idx = i else break
                }
                if (idx >= 0 && positionMs > lyrics[idx].endMs + LINE_GRACE_MS) -1 else idx
            }
        }
    }

    LaunchedEffect(activeLineIdx) {
        if (activeLineIdx >= 0) {
            listState.animateScrollToItem(maxOf(0, activeLineIdx - 2))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 36.dp)
    ) {
        itemsIndexed(lyrics) { idx, line ->
            val isActive = idx == activeLineIdx
            val canSeek = isSynced && line.timestampMs >= 0L
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp)
                    .then(if (canSeek) Modifier.clickable { onSeek(line.timestampMs) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isActive || line.words.isEmpty()) {
                    Text(
                        text = line.text,
                        color = if (isActive) NeoInk else NeoMuted.copy(alpha = 0.6f),
                        fontSize = if (isActive) 21.sp else 16.sp,
                        fontWeight = if (isActive) FontWeight.Black else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // The word being sung is the last word whose start time has passed.
                    val currentWord = line.words.indexOfLast { positionMs >= it.startMs }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.Center
                    ) {
                        line.words.forEachIndexed { wIdx, word ->
                            val isCurrent = wIdx == currentWord
                            val isSung = wIdx < currentWord
                            val wordScale by animateFloatAsState(
                                targetValue = if (isCurrent) 1.12f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "word_scale"
                            )
                            Text(
                                text = word.text + " ",
                                color = when {
                                    isCurrent -> activeColor
                                    isSung -> NeoInk
                                    else -> NeoMuted.copy(alpha = 0.6f)
                                },
                                fontSize = 22.sp,
                                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                                style = TextStyle(
                                    shadow = if (isCurrent) {
                                        Shadow(color = NeonGlow, offset = Offset.Zero, blurRadius = 26f)
                                    } else null
                                ),
                                modifier = Modifier.graphicsLayer {
                                    scaleX = wordScale
                                    scaleY = wordScale
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TagEditorAndFingerprintDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var onlineCoverUri by remember { mutableStateOf<String?>(null) }
    var isFingerprinting by remember { mutableStateOf(false) }
    var matchConfidence by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeoDialog,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text("Fingerprint & Metadata Fixer", color = NeoInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NeoInk, unfocusedTextColor = NeoInk)
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NeoInk, unfocusedTextColor = NeoInk)
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NeoInk, unfocusedTextColor = NeoInk)
                )
                Button(
                    onClick = {
                        scope.launch {
                            isFingerprinting = true
                            val fix = AudioFingerprintFixer.autoFixSong(context, song)
                            if (fix.matchConfidence >= 0.50f) {
                                title = fix.title
                                artist = fix.artist
                                album = fix.album
                                matchConfidence = fix.matchConfidence
                                onlineCoverUri = fix.albumArtUrl
                            } else {
                                Toast.makeText(context, "No match found for this track.", Toast.LENGTH_SHORT).show()
                            }
                            isFingerprinting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepViolet),
                    enabled = !isFingerprinting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GraphicEq, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFingerprinting) "Analyzing Catalog Match..." else "Magic Audio Fingerprint Fix")
                }
                if (matchConfidence > 0f) {
                    Text("• Confidence Match: ${(matchConfidence * 100).toInt()}% • Verified", color = ElectricCyan, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim(), album.trim(), onlineCoverUri) }) {
                Text("Apply & Save", color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MutedLavender) }
        }
    )
}

@Composable
fun DeepOceanLockScreenView(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    gyroPitch: Float,
    gyroRoll: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onCloseLockScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ocean_underwater")
    val waveT1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "ocean_wave1"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepOceanAbyss)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(DeepOceanAbyss, OceanNavyDeep, DeepOceanAbyss)
                )
            )

            val bloomX = w * (0.5f + 0.12f * sin(waveT1.toDouble()).toFloat()) + (gyroRoll * 30f)
            val bloomY = h * (0.35f + 0.08f * cos(waveT1.toDouble()).toFloat()) + (gyroPitch * 30f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BioluminescentCyan.copy(alpha = if (isPlaying) 0.32f else 0.18f),
                        OceanNavyDeep.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(bloomX, bloomY),
                    radius = w * 0.75f
                ),
                radius = w * 0.75f,
                center = Offset(bloomX, bloomY)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = OceanMistGlass,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, OceanGlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = BioluminescentCyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("DEEP OCEAN #18", color = NeoInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                BouncyIconButton(onClick = onCloseLockScreen, size = 36.dp) {
                    Icon(Icons.Default.LockOpen, "Unlock", tint = BioluminescentCyan, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.weight(0.6f))

            RoundedRectTimelineCenterpiece(
                song = song,
                isPlaying = isPlaying,
                primaryColor = OceanNavyDeep,
                secondaryColor = BioluminescentCyan,
                gyroPitch = gyroPitch,
                gyroRoll = gyroRoll,
                modifier = Modifier.size(240.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                song.title,
                color = NeoInk,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${song.artist} • ${song.album}",
                color = MutedLavender.copy(alpha = 0.85f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.weight(0.8f))

            WaveformSeekScrubber(
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                primaryColor = OceanNavyDeep,
                secondaryColor = BioluminescentCyan,
                onSeek = onSeek
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 11.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                color = OceanMistGlass,
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.2.dp, OceanGlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BouncyIconButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, null, tint = if (shuffle) BioluminescentCyan else MutedLavender)
                    }
                    BouncyIconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, null, tint = NeoInk, modifier = Modifier.size(34.dp))
                    }
                    BouncyButton(onClick = onPlayPause, modifier = Modifier.size(62.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = BioluminescentCyan,
                            border = BorderStroke(2.dp, NeoInk),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    null,
                                    tint = DeepOceanAbyss,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                    BouncyIconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, null, tint = NeoInk, modifier = Modifier.size(34.dp))
                    }
                    BouncyIconButton(onClick = onRepeat) {
                        Icon(
                            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            null,
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) BioluminescentCyan else MutedLavender
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    speedMode: String,
    slowedSpeedFactor: Float,
    reverbStrengthFactor: Float,
    onSlowedSpeedChange: (Float) -> Unit,
    onReverbStrengthChange: (Float) -> Unit,
    onSpeedModeChange: (String) -> Unit,
    isFavorite: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    gyroPitch: Float,
    gyroRoll: Float,
    onClose: () -> Unit,
    onOpenLockScreen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLaunchParty: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val context = LocalContext.current
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsResult by remember { mutableStateOf(LyricsResult.NONE) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(song.id, song.title, song.artist, song.durationMs) {
        isLoadingLyrics = true
        lyricsResult = LyricsEngine.loadLyricsForSong(context, song)
        isLoadingLyrics = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        LiquidWaterWaveBackground(
            isPlaying = isPlaying,
            primaryColor = primaryAccent,
            secondaryColor = secondaryAccent,
            pitch = gyroPitch,
            roll = gyroRoll
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(MutedLavender.copy(alpha = 0.45f))
            )
            Spacer(Modifier.height(6.dp))

            // Navigation Bar
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BouncyIconButton(onClick = onClose, size = 38.dp) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = NeoInk, modifier = Modifier.size(26.dp))
                    }
                    Text(
                        "NOW PLAYING",
                        color = MutedLavender,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BouncyIconButton(onClick = onOpenLockScreen, size = 36.dp) {
                        Icon(Icons.Default.Lock, "Lock Screen", tint = BioluminescentCyan, modifier = Modifier.size(20.dp))
                    }
                    BouncyIconButton(onClick = onLaunchParty, size = 36.dp) {
                        Icon(Icons.Default.Headphones, "Party", tint = secondaryAccent, modifier = Modifier.size(20.dp))
                    }
                    BouncyIconButton(onClick = { showLyrics = !showLyrics }, size = 36.dp) {
                        Icon(Icons.Default.Lyrics, "Lyrics", tint = if (showLyrics) secondaryAccent else MutedLavender, modifier = Modifier.size(20.dp))
                    }
                    BouncyIconButton(onClick = onOpenEqualizer, size = 36.dp) {
                        Icon(Icons.Default.Equalizer, "EQ", tint = secondaryAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Normal", "Nightcore", "Slowed").forEach { mode ->
                    val isSel = speedMode == mode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSel) secondaryAccent.copy(alpha = 0.22f) else NeoInset,
                        border = BorderStroke(1.dp, if (isSel) secondaryAccent else NeoEdge),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { onSpeedModeChange(mode) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = when (mode) {
                                    "Nightcore" -> "⚡ 1.25x Nightcore"
                                    "Slowed" -> "🌌 Slowed + Reverb"
                                    else -> "1.0x Normal"
                                },
                                color = if (isSel) secondaryAccent else MutedLavender,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = speedMode == "Slowed") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NeoInset,
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Slowed Speed", color = NeoInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onSlowedSpeedChange((slowedSpeedFactor - 0.05f).coerceIn(0.60f, 0.95f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = secondaryAccent, modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    "%.2fx".format(slowedSpeedFactor),
                                    color = secondaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = { onSlowedSpeedChange((slowedSpeedFactor + 0.05f).coerceIn(0.60f, 0.95f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = secondaryAccent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Slider(
                            value = slowedSpeedFactor,
                            onValueChange = onSlowedSpeedChange,
                            valueRange = 0.60f..0.95f,
                            colors = SliderDefaults.colors(thumbColor = secondaryAccent, activeTrackColor = secondaryAccent),
                            modifier = Modifier.height(24.dp)
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Reverb Level", color = NeoInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onReverbStrengthChange((reverbStrengthFactor - 0.10f).coerceIn(0f, 1f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    "${(reverbStrengthFactor * 100).roundToInt()}%",
                                    color = primaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = { onReverbStrengthChange((reverbStrengthFactor + 0.10f).coerceIn(0f, 1f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Slider(
                            value = reverbStrengthFactor,
                            onValueChange = onReverbStrengthChange,
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = primaryAccent, activeTrackColor = primaryAccent),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (showLyrics) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (!isLoadingLyrics && lyricsResult.lines.isNotEmpty()) {
                        Text(
                            text = buildString {
                                append(lyricsResult.language.ifBlank { "Lyrics" })
                                append(if (lyricsResult.synced) " • time-synced" else " • not time-synced")
                                if (lyricsResult.matchLabel.isNotBlank()) append(" • ${lyricsResult.matchLabel}")
                            },
                            color = MutedLavender,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .neuInset(cornerRadius = 26.dp)
                            .padding(18.dp)
                    ) {
                        when {
                            isLoadingLyrics -> CircularProgressIndicator(
                                color = secondaryAccent,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            lyricsResult.lines.isEmpty() -> Text(
                                "No lyrics found for '${song.title}'.\nUse the Auto-Fix (✨) on this song to correct its title and artist, then reopen the lyrics.",
                                color = MutedLavender,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            else -> LiveSyncedKaraokeLyrics(
                                lyrics = lyricsResult.lines,
                                isSynced = lyricsResult.synced,
                                positionMs = position,
                                durationMs = duration,
                                activeColor = NeonBlue,
                                onSeek = onSeek,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RoundedRectTimelineCenterpiece(
                        song = song,
                        isPlaying = isPlaying,
                        primaryColor = primaryAccent,
                        secondaryColor = secondaryAccent,
                        gyroPitch = gyroPitch,
                        gyroRoll = gyroRoll,
                        modifier = Modifier.size(230.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = NeoInk, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} • ${song.album}", color = MutedLavender, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) NeoDanger else MutedLavender
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            WaveformSeekScrubber(
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                primaryColor = primaryAccent,
                secondaryColor = secondaryAccent,
                onSeek = onSeek
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 11.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 11.sp)
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BouncyIconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, null, tint = if (shuffle) secondaryAccent else MutedLavender)
                }
                BouncyIconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, null, tint = NeoInk, modifier = Modifier.size(36.dp))
                }
                BouncyButton(onClick = onPlayPause, modifier = Modifier.size(70.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .neuRaised(cornerRadius = 35.dp, depth = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null,
                            tint = secondaryAccent,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                BouncyIconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, null, tint = NeoInk, modifier = Modifier.size(36.dp))
                }
                BouncyIconButton(onClick = onRepeat) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        null,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) secondaryAccent else MutedLavender
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun SoniqApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("soniq_user_data", 0) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showPlayer by remember { mutableStateOf(false) }
    var showDeepOceanLockScreen by remember { mutableStateOf(false) }

    var dynamicPrimaryColor by remember { mutableStateOf(NeonPurple) }
    var dynamicSecondaryColor by remember { mutableStateOf(ElectricCyan) }
    var outputs by remember { mutableStateOf<List<AudioOutput>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recents by remember { mutableStateOf<List<String>>(prefs.getString("recents_ordered", "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()) }
    var searchHistory by remember { mutableStateOf<List<String>>(prefs.getString("search_history_ordered", "")?.split("|")?.filter { it.isNotBlank() }?.take(6) ?: emptyList()) }

    var isBatchFixing by remember { mutableStateOf(false) }
    var batchCurrent by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var batchTrackName by remember { mutableStateOf("") }
    var batchSummary by remember { mutableStateOf<String?>(null) }

    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var isScanningDuplicates by remember { mutableStateOf(false) }

    var playlists by remember { mutableStateOf<List<String>>(prefs.getStringSet("playlists", setOf("Favorites", "Workout", "Late Night"))?.toList()?.sorted() ?: listOf("Favorites")) }
    var selectedPlaylist by remember { mutableStateOf("Favorites") }
    var playlistSongs by remember { mutableStateOf<Set<String>>(prefs.getString("playlist_Favorites", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }

    var customAlbums by remember { mutableStateOf<List<String>>(prefs.getStringSet("custom_albums", emptySet())?.toList()?.sorted() ?: emptyList()) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var addToAlbumSong by remember { mutableStateOf<Song?>(null) }

    var timerMinutes by remember { mutableIntStateOf(0) }
    var customTimer by remember { mutableStateOf("25") }
    var timerLeft by remember { mutableIntStateOf(0) }
    var editTagSong by remember { mutableStateOf<Song?>(null) }
    var libraryMode by remember { mutableStateOf("Songs") }
    val listState = rememberLazyListState()
    var selectedFilterKey by remember { mutableStateOf<String?>(null) }
    var playerOffsetY by remember { mutableFloatStateOf(0f) }
    val gyroEngine = remember { GyroscopeParallaxEngine(context) }

    var currentSpeedMode by remember { mutableStateOf("Normal") }
    var slowedSpeedFactor by remember { mutableFloatStateOf(0.85f) }
    var reverbStrengthFactor by remember { mutableFloatStateOf(0.70f) }

    DisposableEffect(Unit) {
        gyroEngine.start()
        onDispose { gyroEngine.stop() }
    }

    BackHandler(enabled = showDeepOceanLockScreen || showPlayer || selectedFilterKey != null || currentTab != MainTab.HOME) {
        when {
            showDeepOceanLockScreen -> showDeepOceanLockScreen = false
            showPlayer -> showPlayer = false
            selectedFilterKey != null -> selectedFilterKey = null
            currentTab != MainTab.HOME -> currentTab = MainTab.HOME
        }
    }

    fun saveFavorites() { prefs.edit().putStringSet("favorites", favorites).apply() }
    fun saveRecents(updated: List<String>) { prefs.edit().putString("recents_ordered", updated.joinToString("|")).apply() }

    fun playSong(song: Song, source: List<Song> = songs, openFullPlayer: Boolean = true) {
        val c = controller ?: return
        val queue = source.ifEmpty { listOf(song) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(queue.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .setArtworkUri(s.albumArtUri?.let(Uri::parse))
                        .build()
                ).build()
        }, index, 0L)
        c.prepare()
        c.play()
        currentSong = song
        isPlaying = true
        duration = song.durationMs
        position = 0L
        val updatedRecents = (listOf(song.id.toString()) + recents.filterNot { it == song.id.toString() }).take(30)
        recents = updatedRecents
        saveRecents(updatedRecents)
        if (openFullPlayer) showPlayer = true
    }

    fun skipTrack(forward: Boolean) {
        val c = controller ?: return
        // After an error the player sits idle; re-prepare it so the skip is not ignored.
        if (c.playerError != null || c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }
        if (forward) {
            when {
                c.hasNextMediaItem() -> c.seekToNextMediaItem()
                c.repeatMode == Player.REPEAT_MODE_ALL -> c.seekToDefaultPosition(0)
                else -> return
            }
        } else {
            // Like most players: a previous tap in the first 3 seconds goes to the previous track.
            if (c.currentPosition > 3000L || !c.hasPreviousMediaItem()) c.seekTo(0L)
            else c.seekToPreviousMediaItem()
        }
        c.play()
    }

    fun triggerBatchFix() {
        if (isBatchFixing) return
        val autoIds = prefs.getStringSet("auto_fixed_ids", emptySet())?.toSet() ?: emptySet()
        // Unknown tracks, plus tracks whose earlier auto-fix is re-verified this time.
        val targets = songs.filter { AudioFingerprintFixer.isSongUnknown(it) || it.id.toString() in autoIds }
        if (targets.isEmpty()) {
            batchSummary = "All tracks in your library are already identified!"
            return
        }

        scope.launch {
            isBatchFixing = true
            batchTotal = targets.size
            batchCurrent = 0
            val revertIds = mutableSetOf<Long>()

            val count = AudioFingerprintFixer.batchFixAllUnknown(
                context = context,
                songs = targets,
                onProgress = { cur, tot, name ->
                    batchCurrent = cur
                    batchTotal = tot
                    batchTrackName = name
                },
                onSongFixed = { song, fix ->
                    val artUri = fix.albumArtUrl ?: song.albumArtUri
                    saveMetadataOverride(prefs, song.id, fix.title, fix.artist, fix.album, artUri)
                    markAutoFixed(prefs, song.id, add = true)

                    songs = songs.map {
                        if (it.id == song.id) it.copy(
                            title = fix.title,
                            artist = fix.artist,
                            album = fix.album,
                            albumArtUri = artUri
                        ) else it
                    }
                    if (currentSong?.id == song.id) {
                        currentSong = currentSong?.copy(
                            title = fix.title,
                            artist = fix.artist,
                            album = fix.album,
                            albumArtUri = artUri
                        )
                    }
                },
                onSongUnmatched = { song ->
                    // An earlier auto-fix that no longer verifies goes back to the file's own tags.
                    if (song.id.toString() in autoIds) revertIds.add(song.id)
                }
            )

            if (revertIds.isNotEmpty()) {
                val rawById = withContext(Dispatchers.IO) {
                    runCatching { MusicRepository(context).loadSongs() }.getOrDefault(emptyList())
                }.associateBy { it.id }
                revertIds.forEach { id ->
                    removeMetadataOverride(prefs, id)
                    markAutoFixed(prefs, id, add = false)
                }
                songs = songs.map { s -> if (s.id in revertIds) rawById[s.id] ?: s else s }
            }

            isBatchFixing = false
            val failed = AudioFingerprintFixer.lastRunNetworkFailures
            val skipped = targets.size - count
            batchSummary = "Auto-fix complete: updated $count of ${targets.size} songs" +
                (if (skipped > 0) ", $skipped had no verified match" else "") + "." +
                (if (failed > 0) " $failed catalog requests failed. Check the internet connection and run it again." else "")
        }
    }

    fun applyPlaybackSettings(mode: String, speed: Float, reverb: Float) {
        val c = controller ?: return
        currentSpeedMode = mode
        runCatching {
            when (mode) {
                "Nightcore" -> {
                    c.playbackParameters = PlaybackParameters(1.25f, 1.25f)
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength(0)
                    }
                }
                "Slowed" -> {
                    c.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 1.5f), speed.coerceIn(0.5f, 1.5f))
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength((reverb * 1000f).roundToInt().coerceIn(0, 1000))
                    }
                }
                else -> {
                    c.playbackParameters = PlaybackParameters(1.0f, 1.0f)
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength(0)
                    }
                }
            }
        }
    }

    fun launchPartyMode() {
        try {
            val intent = Intent("com.samsung.android.settings.BLUETOOTH_CASTING_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
        }
    }

    LaunchedEffect(currentSong?.id, currentSong?.albumArtUri) {
        val song = currentSong ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bytes = MusicRepository.extractEmbeddedPicture(context, song.uri)
            val bmp: Bitmap? = if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
            if (bmp != null) {
                Palette.from(bmp).generate().let { palette ->
                    val dom = palette.getVibrantColor(palette.getDominantColor(NeonPurple.toArgb()))
                    val sec = palette.getLightVibrantColor(palette.getMutedColor(ElectricCyan.toArgb()))
                    dynamicPrimaryColor = Color(dom)
                    dynamicSecondaryColor = Color(sec)
                }
            } else {
                dynamicPrimaryColor = NeonPurple
                dynamicSecondaryColor = ElectricCyan
            }
        }
    }

    LaunchedEffect(Unit) {
        val loaded = runCatching { MusicRepository(context).loadSongs() }.getOrDefault(emptyList())
        songs = applySavedMetadataOverrides(loaded, prefs)
    }

    DisposableEffect(context) {
        val monitor = AudioDeviceMonitor(context) { outputs = it }
        monitor.start()
        onDispose { monitor.stop() }
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        val idStr = item?.mediaId
                        val title = item?.mediaMetadata?.title?.toString()
                        currentSong = songs.firstOrNull { it.id.toString() == idStr }
                            ?: songs.firstOrNull { it.title == title }
                            ?: currentSong
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        duration = c.duration.coerceAtLeast(0L)
                    }
                })
            }
        }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller, songs) {
        while (true) {
            controller?.let { c ->
                position = c.currentPosition.coerceAtLeast(0L)
                val playerDur = c.duration
                if (playerDur > 0) duration = playerDur
                isPlaying = c.isPlaying
                if (c.currentMediaItem != null && currentSong == null && songs.isNotEmpty()) {
                    val idStr = c.currentMediaItem?.mediaId
                    val title = c.currentMediaItem?.mediaMetadata?.title?.toString()
                    currentSong = songs.firstOrNull { it.id.toString() == idStr }
                        ?: songs.firstOrNull { it.title == title }
                }
            }
            delay(150)
        }
    }

    LaunchedEffect(currentSong?.id, isPlaying) {
        NowPlayingState.songId = currentSong?.id
        NowPlayingState.playing = isPlaying
    }

    LaunchedEffect(timerMinutes) {
        timerLeft = timerMinutes * 60
        while (timerLeft > 0 && timerMinutes > 0) {
            delay(1000)
            timerLeft--
        }
        if (timerMinutes > 0 && timerLeft == 0) {
            controller?.pause()
            isPlaying = false
            timerMinutes = 0
        }
    }

    FluidTouchCanvas(rippleColor = dynamicSecondaryColor) {
        Box(Modifier.fillMaxSize()) {
            LiquidWaterWaveBackground(
                isPlaying = isPlaying,
                primaryColor = dynamicPrimaryColor,
                secondaryColor = dynamicSecondaryColor,
                pitch = gyroEngine.pitch,
                roll = gyroEngine.roll
            )
            val bottomPadding = if (currentSong != null) 176.dp else 96.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = bottomPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SONIQ",
                            color = NeoInk,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.4.sp
                        )
                        Text("Liquid Gyro Acoustics • Studio Suite", color = MutedLavender, fontSize = 11.sp)
                    }
                    Surface(
                        color = NeoSurface, shadowElevation = 6.dp,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.clickable { launchPartyMode() }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = "Party Share", tint = dynamicSecondaryColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Party", color = NeoInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Search Bar
                AnimatedVisibility(visible = currentTab == MainTab.HOME || currentTab == MainTab.LIBRARY) {
                    Column {
                        // Liquid-glass search pill: frosted white, blue gradient edge and blue glow
                        val searchShape = RoundedCornerShape(26.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(14.dp, searchShape, ambientColor = GlassGlow, spotColor = GlassGlow)
                                .clip(searchShape)
                                .background(Brush.horizontalGradient(listOf(GlassFrost, Color(0xB3E3F3FF))))
                                .border(1.2.dp, Brush.linearGradient(listOf(GlassBlueA, GlassBlueB)), searchShape)
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    if (it.isNotBlank() && currentTab == MainTab.HOME) {
                                        currentTab = MainTab.LIBRARY
                                    }
                                    if (it.trim().length >= 3) {
                                        val clean = it.trim()
                                        searchHistory = (listOf(clean) + searchHistory.filterNot { old -> old.equals(clean, true) }).take(6)
                                        prefs.edit().putString("search_history_ordered", searchHistory.joinToString("|")).apply()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        "Search songs, artists, albums, folders...",
                                        color = MutedLavender.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = dynamicSecondaryColor) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Default.Close, "Clear", tint = MutedLavender)
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(26.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedTextColor = NeoInk,
                                    unfocusedTextColor = NeoInk,
                                    cursorColor = dynamicSecondaryColor,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                        }

                        if (query.isEmpty() && searchHistory.isNotEmpty() && currentTab == MainTab.LIBRARY) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History, null, tint = MutedLavender.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                searchHistory.forEach { term ->
                                    SuggestionChip(
                                        onClick = { query = term },
                                        label = { Text(term, fontSize = 11.sp, color = MutedLavender) },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(0.8.dp, GlassBorder)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "screen_transition"
                ) { target ->
                    when (target) {
                        MainTab.HOME -> HomeScreen(
                            songs = songs,
                            recents = recents,
                            favorites = favorites,
                            onPlay = { song, list -> playSong(song, list) },
                            onFavorite = { song ->
                                favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                                saveFavorites()
                            },
                            onAddToPlaylist = { addToPlaylistSong = it },
                            onAddToAlbum = { addToAlbumSong = it },
                            onEdit = { editTagSong = it },
                            onNavigateLibrary = { mode ->
                                libraryMode = mode
                                selectedFilterKey = null
                                currentTab = MainTab.LIBRARY
                            }
                        )
                        MainTab.LIBRARY -> LibraryScreen(
                            allSongs = songs,
                            mode = libraryMode,
                            onMode = {
                                libraryMode = it
                                selectedFilterKey = null
                            },
                            filterKey = selectedFilterKey,
                            onFilterKey = { selectedFilterKey = it },
                            query = query,
                            listState = listState,
                            favorites = favorites,
                            recents = recents,
                            playlistName = selectedPlaylist,
                            playlistSongs = playlistSongs,
                            customAlbums = customAlbums,
                            getCustomAlbumSongs = { albumName ->
                                val raw = prefs.getString("album_$albumName", "") ?: ""
                                raw.split("|").filter { it.isNotBlank() }.toSet()
                            },
                            onCreateAlbum = { showCreateAlbum = true },
                            onAutoFixAll = { triggerBatchFix() },
                            onPlay = { song, list -> playSong(song, list) },
                            onFavorite = { song ->
                                favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                                saveFavorites()
                            },
                            onAddToPlaylist = { addToPlaylistSong = it },
                            onAddToAlbum = { addToAlbumSong = it },
                            onEdit = {