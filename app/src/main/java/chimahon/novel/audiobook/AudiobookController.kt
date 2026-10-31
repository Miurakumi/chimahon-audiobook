package chimahon.novel.audiobook

import eu.kanade.tachiyomi.R
import android.content.Context
import android.content.Intent
import chimahon.anki.AnkiSentenceAudioFailure
import chimahon.anki.AnkiSentenceAudioPreparation
import chimahon.anki.AnkiSentenceAudioSource
import chimahon.anki.LazyAnkiSentenceAudioProvider
import com.arthenica.ffmpegkit.FFmpegKitConfig
import eu.kanade.tachiyomi.ui.player.mining.FfmpegCommandResult
import eu.kanade.tachiyomi.ui.player.mining.FfmpegRunner
import java.io.File
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

    /** Background music (immersion mode), independent from the narration player. */
    val music = BackgroundMusic(context, scope)

    /** Detects the mood of the book with a small on-device model ("Auto" button). */
    val auto = AutoMood(context, scope) { labels ->
        state = state.copy(moodLabels = labels)
        persist()
    }

    /** Song mode: the audio keeps playing past the end of each line and the active line follows it. */
    var continuousMode by mutableStateOf(false)

    /** Position and length of the audio in ms (kept up to date while it plays in song mode). */
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    /** Work mode: after each line the dial slides to the next one and plays it by itself. */
    var workMode by mutableStateOf(false)
        private set

    /** True while the music follows the detected mood of the active line. */
    var autoMusic by mutableStateOf(false)
        private set

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
            moodLabels = if (changed) emptyList() else state.moodLabels,
        )
        if (changed) {
            activeIndex = 0
            auto.reset()
            autoMusic = false
        }
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
        auto.cancel()
        autoMusic = false
        workMode = false
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
                    error = context.getString(R.string.dial_err_srt_empty)
                    return@launch
                }
                releasePlayer()
                val mp = createPlayer(audio)
                mp.setOnCompletionListener {
                    isPlaying = false
                    pollJob?.cancel()
                }
                mp.setOnErrorListener { _, what, extra ->
                    error = context.getString(R.string.dial_err_audio, what, extra)
                    isPlaying = false
                    pollJob?.cancel()
                    true
                }
                player = mp
                durationMs = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
                positionMs = 0
                appliedSpeed = 1f
                lines = parsed
                auto.attach(parsed, state.moodLabels)
                activeIndex = activeIndex.coerceIn(0, parsed.lastIndex)
                ready = true
            } catch (e: Exception) {
                error = context.getString(R.string.dial_err_open, e.message ?: e.javaClass.simpleName)
            } finally {
                loading = false
            }
        }
    }

    private fun readText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(context.getString(R.string.dial_err_read_srt))
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
        currentEndMs = if (continuousMode) Long.MAX_VALUE else line.endMs + TAIL_MS
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
            error = context.getString(R.string.dial_err_play, it.message ?: it.javaClass.simpleName)
        }
    }

    private fun startPolling(mp: MediaPlayer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (!seeking) {
                    val position = runCatching { mp.currentPosition }.getOrDefault(Int.MAX_VALUE)
                    if (continuousMode && position != Int.MAX_VALUE) followPosition(position)
                    if (position >= currentEndMs) {
                        pause("fin de la línea (pos $position ms)")
                        if (workMode) scope.launch { advanceWorkMode() }
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
        if (isPlaying) {
            workMode = false
            pause()
        } else if (continuousMode && player != null) {
            // Song mode: play from where it is (0:00 at the start); at the end, start over.
            val mp = player!!
            if (durationMs > 0 && positionMs >= durationMs - 500) {
                runCatching { mp.seekTo(0) }
                positionMs = 0
                activeIndex = 0
                persist()
            }
            startPlayer(mp)
        } else {
            playLine(activeIndex)
        }
    }

    // ---------------------------------------------------------------- song mode

    /** Index of the line that is being sung at [ms] (the last one that has already started). */
    fun lineIndexAt(ms: Long): Int {
        if (lines.isEmpty()) return 0
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lines[mid].startMs <= ms) low = mid else high = mid - 1
        }
        return low
    }

    private fun followPosition(position: Int) {
        positionMs = position
        val index = lineIndexAt(position.toLong())
        if (index != activeIndex) {
            activeIndex = index
            applyAutoMood(index)
            persist()
        }
    }

    /** Slider: jump to [ms] in the audio, keeping the play/pause state. */
    fun seekToMs(ms: Int) {
        val mp = player ?: return
        val target = ms.coerceIn(0, durationMs)
        runCatching { mp.seekTo(target) }
        positionMs = target
        val index = lineIndexAt(target.toLong())
        if (index != activeIndex) {
            activeIndex = index
            persist()
        }
    }

    /** Long press on play: turns work mode on or off. */
    fun toggleWorkMode() {
        workMode = !workMode
        if (workMode && !isPlaying && ready) playLine(activeIndex)
    }

    /** Work mode: slide to the next line and play it (stops at the end of the book). */
    private fun advanceWorkMode() {
        if (!workMode) return
        val next = activeIndex + 1
        if (next !in lines.indices) {
            workMode = false
            return
        }
        activeIndex = next
        persist()
        applyAutoMood(next)
        playLine(next)
    }

    // ---------------------------------------------------------------- dial events

    /** The dial stopped on [index]. Plays it if the mode says so and it is a new line. */
    fun onSettled(index: Int) {
        if (index !in lines.indices) return
        val changed = index != activeIndex
        if (!changed) return
        activeIndex = index
        persist()
        applyAutoMood(index)
        pause("nueva línea")
        if (shouldAutoPlay(lines[index])) playLine(index) else debug = "modo ${mode.name}: no suena sola"
    }

    /** Explicit jump (tap on a line, previous/next). [forcePlay] ignores the mode. */
    fun jumpTo(index: Int, forcePlay: Boolean) {
        if (index !in lines.indices) return
        activeIndex = index
        persist()
        applyAutoMood(index)
        pause()
        if (forcePlay || shouldAutoPlay(lines[index])) playLine(index)
    }

    // ---------------------------------------------------------------- auto music

    /** The "Auto" button: the music follows the mood detected for the active line. */
    fun toggleAuto() {
        if (autoMusic) {
            autoMusic = false
            return
        }
        if (!auto.hasResults) {
            music.showMessage(context.getString(R.string.dial_auto_needs))
            return
        }
        music.showMessage(null)
        autoMusic = true
        applyAutoMood(activeIndex)
    }

    /** Deletes the detected moods (Auto turns off); the book can be analyzed again. */
    fun clearAnalysis() {
        autoMusic = false
        auto.clearResults()
    }

    /** A tap on a mood button: manual choice, so Auto turns off. */
    fun pickMood(mood: Mood) {
        autoMusic = false
        music.select(mood)
    }

    private fun applyAutoMood(index: Int) {
        if (!autoMusic) return
        auto.moodAt(index)?.let { music.switchTo(it) }
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

    // ---------------------------------------------------------------- anki

    /** Cuts the audio of [line] (with a little padding) for the Anki sentence-audio field. */
    fun sentenceAudioProvider(line: SrtLine): LazyAnkiSentenceAudioProvider? {
        val audio = state.audioUri?.let(Uri::parse) ?: return null
        val appContext = context.applicationContext
        return LazyAnkiSentenceAudioProvider {
            withContext<AnkiSentenceAudioPreparation>(Dispatchers.IO) {
                val out = File(appContext.cacheDir, "chimahon_dial_${System.currentTimeMillis()}.m4a")
                try {
                    val input = FFmpegKitConfig.getSafParameterForRead(appContext, audio)
                        ?: return@withContext AnkiSentenceAudioPreparation.Unavailable(
                            AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE,
                        )
                    val start = (line.startMs - CLIP_PAD_MS).coerceAtLeast(0L)
                    val end = line.endMs + CLIP_PAD_MS
                    val args = arrayOf(
                        "-ss", "%.3f".format(java.util.Locale.US, start / 1000.0),
                        "-t", "%.3f".format(java.util.Locale.US, (end - start) / 1000.0),
                        "-i", input,
                        "-vn", "-sn", "-dn",
                        "-c:a", "aac", "-b:a", "128k",
                        "-y", out.absolutePath,
                    )
                    val result = FfmpegRunner.ffmpeg(args)
                    if (result !is FfmpegCommandResult.Success || !out.isFile || out.length() <= 0L) {
                        return@withContext AnkiSentenceAudioPreparation.Unavailable(
                            AnkiSentenceAudioFailure.EXTRACTION_FAILED,
                        )
                    }
                    AnkiSentenceAudioPreparation.Ready(
                        AnkiSentenceAudioSource.fromBytes(out.readBytes(), "m4a"),
                    )
                } finally {
                    out.delete()
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
        workMode = false
        pause()
        persist()
        releasePlayer()
        auto.cancel()
        music.release()
    }

    companion object {
        private const val TAIL_MS = 120L
        private const val CLIP_PAD_MS = 100L
        private const val POLL_MS = 20L
        private const val SEEK_TIMEOUT_MS = 2000L
        val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
    }
}
