package chimahon.novel.audiobook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Plain mutable flag that does not trigger recomposition. */
private class Flag(var value: Boolean = false)

/**
 * Full-screen audiobook dial, shown on top of the EPUB reader.
 *
 * The list of subtitle lines scrolls under a fixed viewer strip. The line sitting on the
 * strip is the active one: when the list stops, the audio seeks to that line, plays it and
 * pauses. Tapping a character of the active line opens the dictionary.
 *
 * @param onLookup word (text from the tapped character to the end of the sentence), the full
 *   line, the tapped offset inside the line, and the anchor box (x, y, w, h) in window pixels.
 */
@Composable
fun AudiobookScreen(
    bookKey: String,
    title: String,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(bookKey) { AudiobookController(context, bookKey, scope) }
    DisposableEffect(controller) { onDispose { controller.release() } }

    // Surface sets the default text/icon color from the reader theme (otherwise they are black).
    Surface(
        modifier = modifier
            .fillMaxSize()
            // Swallow taps so nothing reaches the EPUB underneath.
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver al libro")
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (controller.hasFiles) {
                TextButton(onClick = { controller.changeFiles() }) { Text("Archivos") }
            }
        }

        when {
            !controller.hasFiles || controller.error != null -> SetupPane(
                controller = controller,
                modifier = Modifier.weight(1f),
            )
            controller.loading || !controller.ready -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                Dial(
                    controller = controller,
                    isPopupActive = isPopupActive,
                    onLookup = onLookup,
                    onDismissPopup = onDismissPopup,
                    modifier = Modifier.weight(1f),
                )
                Controls(controller = controller)
            }
        }
    }
    }
}

// ------------------------------------------------------------------ setup

@Composable
private fun SetupPane(controller: AudiobookController, modifier: Modifier = Modifier) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setAudio(uri)
    }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setSrt(uri)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Audiolibro", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Elegí el audio y el SRT de este libro. Se guardan y la próxima vez abre directo donde quedaste.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )

        controller.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        Button(
            onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(controller.state.audioName?.let { "Audio: $it" } ?: "Elegir audio")
        }
        Button(
            onClick = { srtPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(controller.state.srtName?.let { "SRT: $it" } ?: "Elegir SRT")
        }

        if (controller.hasFiles && controller.error != null) {
            OutlinedButton(
                onClick = { controller.retry() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reintentar") }
        }
    }
}

// ------------------------------------------------------------------ dial

@Composable
private fun Dial(
    controller: AudiobookController,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = controller.activeIndex)
    val programmatic = remember { Flag() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        // The viewer strip is fixed at ~1/3 of the list area. The list gets matching
        // padding above and below so the first and last lines can also reach the strip.
        val topPadPx = heightPx * 0.32f
        val minLinePx = with(density) { 56.dp.toPx() }
        val bottomPadPx = (heightPx - topPadPx - minLinePx).coerceAtLeast(0f)
        val topPad = with(density) { topPadPx.toDp() }
        val bottomPad = with(density) { bottomPadPx.toDp() }

        /** Line whose top edge is closest to the viewer strip. */
        fun nearestIndex(): Int? {
            val info = listState.layoutInfo
            val viewportStart = info.viewportStartOffset
            return info.visibleItemsInfo
                .minByOrNull { abs((it.offset - viewportStart) - topPadPx) }
                ?.index
        }

        suspend fun alignTo(index: Int) {
            programmatic.value = true
            try {
                listState.animateScrollToItem(index)
            } finally {
                programmatic.value = false
            }
        }

        // The LaunchedEffect on controller.activeIndex (below) moves the list to the new line.
        fun goTo(index: Int, forcePlay: Boolean) {
            controller.jumpTo(index, forcePlay)
        }

        // When the user stops scrolling: take the line on the strip, play it, snap to it.
        LaunchedEffect(listState, topPadPx) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (!scrolling && !programmatic.value) {
                        val index = nearestIndex() ?: return@collect
                        controller.onSettled(index)
                        alignTo(index)
                    }
                }
        }

        // Moving the list closes an open dictionary popup.
        LaunchedEffect(listState, isPopupActive) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling -> if (scrolling && isPopupActive) onDismissPopup() }
        }

        // The viewer strip.
        Box(
            modifier = Modifier
                .offset(y = topPad)
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 10.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(16.dp),
                ),
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = controller.lines.size, key = { it }) { index ->
                val line = controller.lines[index]
                val active = index == controller.activeIndex
                val target = when (abs(index - controller.activeIndex)) {
                    0 -> 1f
                    1 -> 0.55f
                    2 -> 0.35f
                    else -> 0.18f
                }
                val fog by animateFloatAsState(target, label = "fog")

                DialLine(
                    text = line.text,
                    active = active,
                    fog = fog,
                    onJump = {
                        if (isPopupActive) onDismissPopup()
                        goTo(index, forcePlay = true)
                    },
                    onBackgroundTap = { if (isPopupActive) onDismissPopup() },
                    onLookupAt = { offset, anchor ->
                        val word = wordFrom(line.text, offset)
                        if (word.isNotBlank()) {
                            controller.pause()
                            onLookup(
                                word,
                                line.text,
                                offset,
                                anchor.left,
                                anchor.top,
                                anchor.width,
                                anchor.height,
                            )
                        }
                    },
                )
            }
        }

        LaunchedEffect(controller.activeIndex) {
            // Taps and the previous/next buttons change the active line without touching the
            // list: move the list so that line lands on the viewer strip.
            val index = controller.activeIndex
            val visible = listState.layoutInfo.visibleItemsInfo
            val aligned = visible.firstOrNull { it.index == index }
                ?.let { abs((it.offset - listState.layoutInfo.viewportStartOffset) - topPadPx) < 2f } == true
            if (!aligned && !listState.isScrollInProgress && !programmatic.value) alignTo(index)
        }
    }
}

/** Anchor box of the tapped character, in window pixels. */
private data class Anchor(val left: Float, val top: Float, val width: Float, val height: Float)

/** Text from [offset] to the end of the sentence, as the reader sends it to the dictionary. */
private fun wordFrom(text: String, offset: Int): String {
    if (offset !in text.indices) return ""
    val tail = text.substring(offset)
    val end = tail.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' || it == '\n' }
    val sentence = if (end >= 0) tail.substring(0, end) else tail
    return sentence.trim().take(40)
}

@Composable
private fun DialLine(
    text: String,
    active: Boolean,
    fog: Float,
    onJump: () -> Unit,
    onBackgroundTap: () -> Unit,
    onLookupAt: (Int, Anchor) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fog }
            .pointerInput(active) {
                detectTapGestures {
                    if (active) onBackgroundTap() else onJump()
                }
            },
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            lineHeight = 34.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 14.dp)
                .onGloballyPositioned { origin = it.positionInWindow() }
                .pointerInput(active, text) {
                    if (active) {
                        detectTapGestures { position ->
                            val result = layout ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                                .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                            val box = result.getBoundingBox(offset)
                            onLookupAt(
                                offset,
                                Anchor(
                                    left = origin.x + box.left,
                                    top = origin.y + box.top,
                                    width = box.width,
                                    height = box.height,
                                ),
                            )
                        }
                    }
                },
        )
    }
}

// ------------------------------------------------------------------ controls

@Composable
private fun Controls(controller: AudiobookController) {
    val total = controller.lines.size
    val active = controller.activeIndex

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        LinearProgressIndicator(
            progress = { if (total > 0) (active + 1f) / total else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
        )
        Text(
            text = "${active + 1} / $total",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { controller.cycleMode() }) {
                Text(
                    when (controller.mode) {
                        PlayMode.ALL -> "Todas"
                        PlayMode.QUOTES -> "Citas"
                        PlayMode.MANUAL -> "Manual"
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.step(-1) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Línea anterior")
                }
                FilledIconButton(onClick = { controller.togglePlay() }) {
                    Icon(
                        imageVector = if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controller.isPlaying) "Pausar" else "Reproducir línea",
                    )
                }
                IconButton(onClick = { controller.step(1) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Línea siguiente")
                }
            }

            TextButton(onClick = { controller.cycleSpeed() }) {
                Text("${controller.state.speed}x")
            }
        }
    }
}
