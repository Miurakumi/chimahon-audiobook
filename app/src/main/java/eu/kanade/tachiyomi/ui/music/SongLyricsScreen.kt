package eu.kanade.tachiyomi.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.novel.audiobook.AudiobookController
import chimahon.novel.audiobook.AudiobookMining
import eu.kanade.tachiyomi.R

/**
 * Lyrics player (Spotify style): cover and title on top, big lyrics that follow the song with
 * the current line highlighted, and the progress bar + controls at the bottom. Tapping a line
 * jumps there; tapping a word of the highlighted line opens the dictionary.
 */
@Composable
fun SongLyricsScreen(
    bookKey: String,
    title: String,
    artist: String?,
    cover: ByteArray?,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(bookKey) {
        AudiobookController(context, bookKey, scope).also { it.continuousMode = true }
    }
    // A song always opens at its beginning (0:00), not at the line saved last time.
    LaunchedEffect(controller.ready) {
        if (controller.ready) controller.seekToMs(0)
    }
    DisposableEffect(controller) {
        onDispose {
            AudiobookMining.provider = null
            controller.release()
        }
    }

    val coverBitmap = remember(cover) { cover?.let { MusicCovers.decodeBitmap(it) } }
    // No cover: follow the app theme color.
    val themeColor = MaterialTheme.colorScheme.primary
    val accent = remember(coverBitmap, themeColor) { coverBitmap?.let { MusicCovers.averageColor(it) } ?: themeColor }
    val topColor = remember(accent) { lerp(accent, Color.Black, 0.25f) }
    val bottomColor = remember(accent) { lerp(accent, Color.Black, 0.8f) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
                // Swallow taps so nothing reaches what is underneath.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.song_dial_back))
                    }
                    coverBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        artist?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                when {
                    controller.error != null -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(controller.error.orEmpty(), modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
                    }
                    !controller.ready -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    else -> {
                        Lyrics(
                            controller = controller,
                            isPopupActive = isPopupActive,
                            onLookup = onLookup,
                            onDismissPopup = onDismissPopup,
                            modifier = Modifier.weight(1f),
                        )
                        PlayerBar(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun Lyrics(
    controller: AudiobookController,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = controller.activeIndex)

    // The list follows the song: the active line goes to the top of the list.
    LaunchedEffect(controller.activeIndex) {
        runCatching { listState.animateScrollToItem(controller.activeIndex) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 320.dp),
    ) {
        items(count = controller.lines.size, key = { it }) { index ->
            val line = controller.lines[index]
            LyricLine(
                text = line.text,
                active = index == controller.activeIndex,
                onJump = {
                    if (isPopupActive) onDismissPopup()
                    controller.jumpTo(index, forcePlay = true)
                },
                onBackgroundTap = { if (isPopupActive) onDismissPopup() },
                onLookupAt = { offset, left, top, width, height ->
                    val word = lookupWordFrom(line.text, offset)
                    if (word.isNotBlank()) {
                        controller.pause()
                        AudiobookMining.provider = controller.sentenceAudioProvider(line)
                        onLookup(word, line.text, offset, left, top, width, height)
                    }
                },
            )
        }
    }
}

@Composable
private fun LyricLine(
    text: String,
    active: Boolean,
    onJump: () -> Unit,
    onBackgroundTap: () -> Unit,
    onLookupAt: (Int, Float, Float, Float, Float) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val alpha by animateFloatAsState(if (active) 1f else 0.42f, label = "lyricAlpha")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(active) {
                detectTapGestures { if (active) onBackgroundTap() else onJump() }
            },
    ) {
        Text(
            text = text,
            fontSize = 28.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = alpha),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .onGloballyPositioned { origin = it.positionInWindow() }
                .pointerInput(active, text) {
                    if (active) {
                        detectTapGestures { position ->
                            val result = layout ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                                .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                            val box = result.getBoundingBox(offset)
                            onLookupAt(offset, origin.x + box.left, origin.y + box.top, box.width, box.height)
                        }
                    }
                },
        )
    }
}

@Composable
private fun PlayerBar(controller: AudiobookController) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = controller.durationMs
    val progress = if (duration > 0) controller.positionMs.toFloat() / duration else 0f
    val shownMs = if (dragging) (dragValue * duration).toInt() else controller.positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Slider(
            value = if (dragging) dragValue else progress,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                controller.seekToMs((dragValue * duration).toInt())
                dragging = false
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shownMs), style = MaterialTheme.typography.labelSmall)
            Text(clock(duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.step(-1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.dial_prev), modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { controller.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (controller.isPlaying) R.string.dial_pause else R.string.music_play),
                    tint = Color.Black,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = { controller.step(1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.dial_next), modifier = Modifier.size(34.dp))
            }
        }
    }
}

private fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Text from [offset] to the end of the sentence, as the dictionary expects it. */
private fun lookupWordFrom(text: String, offset: Int): String {
    if (offset !in text.indices) return ""
    val tail = text.substring(offset)
    val end = tail.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' || it == '\n' }
    val sentence = if (end >= 0) tail.substring(0, end) else tail
    return sentence.trim().take(40)
}
