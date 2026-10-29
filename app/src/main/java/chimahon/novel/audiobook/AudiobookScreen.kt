package chimahon.novel.audiobook

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How many chunks the "Analizar" button reads each time. */
private const val AUTO_BATCH = 20

/**
 * Bookkeeping for the list alignment, kept out of Compose state on purpose.
 * [count] is how many alignment animations are running (a counter, not a boolean, so an
 * old cancelled animation cannot switch the flag off while a newer one is still running).
 */
private class AlignGuard {
    var count = 0
    var job: Job? = null
    val active: Boolean get() = count > 0
}

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
    fontName: String,
    fontUrl: String?,
    fontSize: Float,
    lineHeight: Float,
    characterSpacing: Float,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(bookKey) { AudiobookController(context, bookKey, scope) }
    var showMusicDialog by remember { mutableStateOf(false) }
    if (showMusicDialog) {
        MusicDialog(
            music = controller.music,
            auto = controller.auto,
            onDismiss = { showMusicDialog = false },
        )
    }
    DisposableEffect(controller) {
        onDispose {
            AudiobookMining.provider = null
            controller.release()
        }
    }
    val fontFamily: FontFamily? = remember(fontName, fontUrl) {
        when {
            fontUrl != null -> runCatching {
                FontFamily(Font(File(Uri.parse(fontUrl).path.orEmpty())))
            }.getOrNull()
            fontName.contains("Sans", ignoreCase = true) -> FontFamily.SansSerif
            fontName.contains("Serif", ignoreCase = true) -> FontFamily.Serif
            else -> null
        }
    }

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
            IconButton(onClick = { showMusicDialog = true }) {
                Icon(Icons.Filled.MusicNote, contentDescription = "Música de fondo")
            }
            if (controller.hasFiles) {
                IconButton(onClick = { controller.changeFiles() }) {
                    Icon(Icons.Filled.Folder, contentDescription = "Cambiar archivos")
                }
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
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    characterSpacing = characterSpacing,
                    isPopupActive = isPopupActive,
                    onLookup = onLookup,
                    onDismissPopup = onDismissPopup,
                    modifier = Modifier.weight(1f),
                )
                Controls(
                    controller = controller,
                    onOpenMusicSettings = { showMusicDialog = true },
                )
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
    fontFamily: FontFamily?,
    fontSize: Float,
    lineHeight: Float,
    characterSpacing: Float,
    isPopupActive: Boolean,
    onLookup: (String, String, Int, Float, Float, Float, Float) -> Unit,
    onDismissPopup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = controller.activeIndex)
    val programmatic = remember { AlignGuard() }

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

        /** How far [index] is from the viewer strip, in pixels (null if not on screen). */
        fun misalignment(index: Int): Float? {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
            return (item.offset - info.viewportStartOffset) - topPadPx
        }

        suspend fun alignTo(index: Int) {
            programmatic.count++
            try {
                listState.animateScrollToItem(index)
            } finally {
                programmatic.count--
            }
        }

        // Alignment runs in its own coroutine. If the user touches the list mid-animation,
        // Android cancels the animation; that cancellation must stay inside this job and
        // never reach the effects below, or they would stop listening for good.
        fun launchAlign(index: Int) {
            programmatic.job?.cancel()
            programmatic.job = scope.launch { alignTo(index) }
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
                    if (!scrolling && !programmatic.active) {
                        val index = nearestIndex() ?: return@collect
                        controller.onSettled(index)
                        // Aligning always flips "scrolling" on and off, which would trigger
                        // this very block again: only move the list if it is really off.
                        val off = misalignment(index)
                        if (off == null || abs(off) >= 1.5f) launchAlign(index)
                    }
                }
        }

        // Moving the list closes an open dictionary popup.
        LaunchedEffect(listState, isPopupActive) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    // Only the user's own scrolling closes the popup, not the automatic snap.
                    if (scrolling && isPopupActive && !programmatic.active) onDismissPopup()
                }
        }

        // The viewer strip. Its height follows the line that is currently under it, so long
        // lines (several rows) are always fully covered and short ones do not get extra space.
        val stripHeightPx by remember(topPadPx) {
            derivedStateOf {
                val info = listState.layoutInfo
                val viewportStart = info.viewportStartOffset
                info.visibleItemsInfo
                    .minByOrNull { abs((it.offset - viewportStart) - topPadPx) }
                    ?.size ?: 0
            }
        }
        val stripHeight by animateDpAsState(
            targetValue = maxOf(with(density) { stripHeightPx.toDp() }, 56.dp),
            label = "strip",
        )
        Box(
            modifier = Modifier
                .offset(y = topPad)
                .fillMaxWidth()
                .height(stripHeight)
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
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    characterSpacing = characterSpacing,
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
                            AudiobookMining.provider = controller.sentenceAudioProvider(line)
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
            if (!aligned && !listState.isScrollInProgress && !programmatic.active) launchAlign(index)
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
    fontFamily: FontFamily?,
    fontSize: Float,
    lineHeight: Float,
    characterSpacing: Float,
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
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            lineHeight = (fontSize * lineHeight).sp,
            letterSpacing = characterSpacing.em,
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
private fun Controls(controller: AudiobookController, onOpenMusicSettings: () -> Unit) {
    val total = controller.lines.size
    val active = controller.activeIndex
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            // Menu: playback options and background music, instead of loose buttons.
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Menú",
                        modifier = Modifier.size(30.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    MenuLabel("Reproducción")
                    // These two cycle through their values and keep the menu open.
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Modo: " + when (controller.mode) {
                                    PlayMode.ALL -> "Todas"
                                    PlayMode.QUOTES -> "Citas"
                                    PlayMode.MANUAL -> "Manual"
                                },
                            )
                        },
                        onClick = { controller.cycleMode() },
                    )
                    DropdownMenuItem(
                        text = { Text("Velocidad: ${controller.state.speed}x") },
                        onClick = { controller.cycleSpeed() },
                    )
                    HorizontalDivider()
                    MenuLabel("Música de fondo")
                    MoodMenuItem(
                        label = if (controller.autoMusic) "Auto · activado" else "Auto",
                        accent = Color(0xFF7C6CF0),
                        selected = controller.autoMusic,
                        onClick = {
                            controller.toggleAuto()
                            menuOpen = false
                        },
                    )
                    Mood.entries.forEach { mood ->
                        MoodMenuItem(
                            label = mood.label,
                            accent = Color(mood.color),
                            selected = controller.music.mood == mood,
                            onClick = {
                                controller.pickMood(mood)
                                menuOpen = false
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Ajustes de música…") },
                        leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenMusicSettings()
                        },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { controller.step(-1) },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Línea anterior",
                        modifier = Modifier.size(34.dp),
                    )
                }
                FilledIconButton(
                    onClick = { controller.togglePlay() },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        modifier = Modifier.size(42.dp),
                        imageVector = if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (controller.isPlaying) "Pausar" else "Reproducir línea",
                    )
                }
                IconButton(
                    onClick = { controller.step(1) },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Línea siguiente",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            // Keeps the play controls centered (same width as the menu button).
            Spacer(modifier = Modifier.size(56.dp))
        }

        controller.music.message?.let {
            Text(
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * A mood (or Auto) entry of the menu. Selected: soft tinted background, bold text, bright dot
 * and a check mark. The background change is animated.
 */
@Composable
private fun MoodMenuItem(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(durationMillis = 350),
        label = "moodMenuBackground",
    )
    DropdownMenuItem(
        text = {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        },
        onClick = onClick,
        modifier = Modifier.background(background),
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (selected) 1f else 0.35f)),
            )
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

// ------------------------------------------------------------------ music settings

@Composable
private fun MusicDialog(music: BackgroundMusic, auto: AutoMood, onDismiss: () -> Unit) {
    var pending by remember { mutableStateOf<Mood?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val mood = pending
        if (uri != null && mood != null) music.setFolder(mood, uri)
        pending = null
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) auto.importModel(uri)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Música de fondo") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Elegí una carpeta con tu música para cada clima. Suena una pista al azar de la carpeta.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Mood.entries.forEach { mood ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mood.label, modifier = Modifier.weight(0.4f))
                        TextButton(
                            onClick = {
                                pending = mood
                                folderPicker.launch(null)
                            },
                            modifier = Modifier.weight(0.6f),
                        ) {
                            Text(
                                music.folderName(mood) ?: "Elegir carpeta",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text("Volumen", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = music.config.volume,
                    onValueChange = { music.setVolume(it) },
                    onValueChangeFinished = { music.commitVolume() },
                )

                // ---- Auto (experimental): a small on-device model reads the SRT.
                Text("Auto (experimental)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Un modelo chico (Qwen3 0.6B, unos 330 MB) corre en el celu y lee el SRT para detectar el clima de cada parte.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val progress = auto.downloadProgress
                when {
                    progress != null -> {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Copiando/bajando el modelo… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Copiando/bajando el modelo…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    !auto.modelReady -> {
                        Button(onClick = { auto.downloadModel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Bajar el modelo")
                        }
                        TextButton(
                            onClick = { modelPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Elegir archivo del modelo") }
                    }
                    else -> Text("Modelo listo.", style = MaterialTheme.typography.bodySmall)
                }
                if (auto.modelReady) {
                    Text(
                        "Analizadas ${auto.analyzedCount} de ${auto.chunks.size} tandas" +
                            (auto.secondsPerChunk?.let { " · ${"%.1f".format(it)} s por tanda" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (auto.running) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { auto.cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Parar análisis")
                        }
                    } else {
                        Button(
                            onClick = { auto.analyze(AUTO_BATCH) },
                            enabled = auto.chunks.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Analizar $AUTO_BATCH tandas") }
                        OutlinedButton(
                            onClick = { auto.analyze(null) },
                            enabled = auto.chunks.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Analizar todo el libro") }
                    }
                    if (auto.chunks.isEmpty()) {
                        Text("Cargá primero el audio y el SRT del libro.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                auto.status?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (auto.analyzedCount > 0) {
                    Text("Lo que detectó:", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        items(auto.chunks.indices.filter { auto.labels[it].isNotEmpty() }) { i ->
                            val chunk = auto.chunks[i]
                            val mood = auto.labelOf(i)
                            Text(
                                "${chunk.start + 1}–${chunk.end + 1} · ${mood?.label ?: "sin clima"} · " +
                                    chunk.text.replace('\n', ' ').take(24),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}
