package chimahon.novel.audiobook

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Holds everything the audiobook dial needs: subtitle lines, the audio player,
 * the active line, and the per-book saved state.
 *
 * The dial drives the audio, never the other way around: the audio plays one line
 * (seek to its start, pause at its end) and then waits for the user to move.
 *
 * Must be created and used on the main thread ([scope] should be a main-thread scope,
 * e.g. the one from rememberCoroutineScope()).
 */
class AudiobookController(
    private val context: Context,
    private val bookKey: String,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(AudiobookState())
        private set
    var lines by mutableStateOf<List<SrtLine>>(emptyList())
        private set
    var activeIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** One-line status of the audio, shown on screen to help find problems. */
    var debug by mutableStateOf("")
        private set

    val mode: PlayMode
        get() = runCatching { PlayMode.valueOf(state.mode) }.getOrDefault(PlayMode.ALL)

    val hasFiles: Boolean
        get() = state.audioUri != null && state.srtUri != null

    private var player: MediaPlayer? = null
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private var currentEndMs = 0L
    private var pendingPlay = false
    private var seeking = false
    private var appliedSpeed = 1f

    init {
        state = AudiobookStore.load(context, bookKey)
        activeIndex = state.lineIndex.coerceAtLeast(0)
        if (hasFiles) load()
    }

    // ---------------------------------------------------------------- files

    fun setAudio(uri: Uri) {
        keepAccess(uri)
        state = state.copy(audioUri = uri.toString(), audioName = displayName(uri))
        persist()
        if (hasFiles) load()
    }

    fun setSrt(uri: Uri) {
        keepAccess(uri)
        val changed = state.srtUri != uri.toString()
        state = state.copy(
            srtUri = uri.toString(),
            srtName = displayName(uri),
            lineIndex = if (changed) 0 else state.lineIndex,
        )
        if (changed) activeIndex = 0
        persist()
        if (hasFiles) load()
    }

    /** Back to the file picker (keeps nothing playing). */
    fun changeFiles() {
        pause()
        releasePlayer()
        ready = false
        error = null
        lines = emptyList()
        state = state.copy(audioUri = null, srtUri = null, audioName = null, srtName = null)
        persist()
    }

    private fun keepAccess(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment

    // ---------------------------------------------------------------- loading

    private fun load() {
        val audio = state.audioUri?.let { Uri.parse(it) } ?: return
        val srt = state.srtUri?.let { Uri.parse(it) } ?: return
        loading = true
        ready = false
        error = null
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { SrtParser.parse(readText(srt)) }
                if (parsed.isEmpty()) {
                    error = "El SRT no tiene líneas válidas."
                    return@launch
                }
                releasePlayer()
                val mp = createPlayer(audio)
                mp.setOnCompletionListener {
                    isPlaying = false
                    pollJob?.cancel()
                }
                mp.setOnErrorListener { _, what, extra ->
                    error = "Error de audio ($what/$extra)"
                    isPlaying = false
                    pollJob?.cancel()
                    true
                }
                player = mp
                appliedSpeed = 1f
                lines = parsed
                activeIndex = activeIndex.coerceIn(0, parsed.lastIndex)
                ready = true
            } catch (e: Exception) {
                error = "No se pudo abrir el audio o el SRT. Elegilos de nuevo. (${e.message ?: e.javaClass.simpleName})"
            } finally {
                loading = false
            }
        }
    }

    private fun readText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("No se pudo leer el SRT")
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private suspend fun createPlayer(uri: Uri): MediaPlayer = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        cont.invokeOnCancellation { runCatching { mp.release() } }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener { if (cont.isActive) cont.resume(mp) }
            mp.setOnErrorListener { _, what, extra ->
                if (cont.isActive) {
                    runCatching { mp.release() }
                    cont.resumeWithException(IllegalStateException("MediaPlayer $what/$extra"))
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    // ---------------------------------------------------------------- playback

    /** Seek to the start of [index], play until its end, then pause. */
    fun playLine(index: Int) {
        val mp = player
        val line = lines.getOrNull(index)
        if (mp == null || line == null) {
            debug = "sin reproductor listo"
            return
        }
        pause("nueva línea")
        currentEndMs = line.endMs + TAIL_MS
        pendingPlay = true
        seeking = true
        debug = "buscando ${line.startMs} ms"
        mp.setOnSeekCompleteListener {
            seeking = false
            watchdogJob?.cancel()
            if (pendingPlay) startPlayer(mp)
        }
        // Long files can take a while to seek, and some devices never report completion
        // when the target equals the current position: start anyway after a wait.
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(SEEK_TIMEOUT_MS)
            if (pendingPlay && seeking) {
                seeking = false
                startPlayer(mp)
            }
        }
        runCatching { mp.seekTo(line.startMs, MediaPlayer.SEEK_CLOSEST) }
            .onFailure {
                seeking = false
                debug = "seek falló: ${it.message}"
                startPlayer(mp)
            }
    }

    private fun startPlayer(mp: MediaPlayer) {
        pendingPlay = false
        runCatching {
            // Changing the speed on a prepared MediaPlayer also starts it, so it is only
            // touched when the speed really changed; start() below is then harmless.
            if (appliedSpeed != state.speed) {
                mp.playbackParams = mp.playbackParams.setSpeed(state.speed)
                appliedSpeed = state.speed
            }
            mp.start()
            isPlaying = true
            debug = "suena: ${mp.currentPosition} ms -> ${currentEndMs} ms"
            startPolling(mp)
        }.onFailure {
            isPlaying = false
            debug = "no arrancó: ${it.message ?: it.javaClass.simpleName}"
            error = "No se pudo reproducir (${it.message ?: it.javaClass.simpleName})"
        }
    }

    private fun startPolling(mp: MediaPlayer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (!seeking) {
                    val position = runCatching { mp.currentPosition }.getOrDefault(Int.MAX_VALUE)
                    if (position >= currentEndMs) {
                        pause("fin de la línea (pos $position ms)")
                        break
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    fun pause(reason: String = "pausa") {
        pendingPlay = false
        seeking = false
        watchdogJob?.cancel()
        pollJob?.cancel()
        runCatching { if (player?.isPlaying == true) player?.pause() }
        isPlaying = false
        debug = reason
    }

    /** Reload the files after an error (or after permission was lost and re-granted). */
    fun retry() {
        if (hasFiles) load()
    }

    /** Previous/next buttons: move the dial by [delta] lines, obeying the play mode. */
    fun step(delta: Int) {
        val target = (activeIndex + delta).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        if (target != activeIndex) jumpTo(target, forcePlay = false)
    }

    /** Play/pause button: replays the active line from its start. */
    fun togglePlay() {
        if (isPlaying) pause() else playLine(activeIndex)
    }

    // ---------------------------------------------------------------- dial events

    /** The dial stopped on [index]. Plays it if the mode says so and it is a new line. */
    fun onSettled(index: Int) {
        if (index !in lines.indices) return
        val changed = index != activeIndex
        if (!changed) return
        activeIndex = index
        persist()
        pause("nueva línea")
        if (shouldAutoPlay(lines[index])) playLine(index) else debug = "modo ${mode.name}: no suena sola"
    }

    /** Explicit jump (tap on a line, previous/next). [forcePlay] ignores the mode. */
    fun jumpTo(index: Int, forcePlay: Boolean) {
        if (index !in lines.indices) return
        activeIndex = index
        persist()
        pause()
        if (forcePlay || shouldAutoPlay(lines[index])) playLine(index)
    }

    private fun shouldAutoPlay(line: SrtLine): Boolean = when (mode) {
        PlayMode.ALL -> true
        PlayMode.QUOTES -> line.isQuote
        PlayMode.MANUAL -> false
    }

    // ---------------------------------------------------------------- settings

    fun cycleMode() {
        val next = PlayMode.entries[(mode.ordinal + 1) % PlayMode.entries.size]
        state = state.copy(mode = next.name)
        persist()
    }

    fun cycleSpeed() {
        val index = SPEEDS.indexOfFirst { it == state.speed }
        val next = SPEEDS[(index + 1) % SPEEDS.size]
        state = state.copy(speed = next)
        persist()
        if (isPlaying) {
            runCatching {
                player?.let {
                    it.playbackParams = it.playbackParams.setSpeed(next)
                    appliedSpeed = next
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun persist() {
        state = state.copy(lineIndex = activeIndex)
        AudiobookStore.save(context, bookKey, state)
    }

    private fun releasePlayer() {
        pollJob?.cancel()
        watchdogJob?.cancel()
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }

    /** Call when the dial is closed. Saves position and frees the player. */
    fun release() {
        pause()
        persist()
        releasePlayer()
    }

    companion object {
        private const val TAIL_MS = 120L
        private const val POLL_MS = 20L
        private const val SEEK_TIMEOUT_MS = 2000L
        val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
    }
}
