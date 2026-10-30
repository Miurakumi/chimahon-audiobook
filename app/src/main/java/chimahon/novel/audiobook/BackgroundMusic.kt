package chimahon.novel.audiobook

import eu.kanade.tachiyomi.R
import androidx.annotation.StringRes
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scene moods the background music can follow. [auto] = the Auto detector may pick it
 * (ANIME_SONG is manual only: the model would overuse it).
 */
enum class Mood(@StringRes val labelRes: Int, val color: Long, val auto: Boolean = true) {
    ANIME_SONG(R.string.dial_mood_anime_song, 0xFF9C6BFF, auto = false),
    COMEDY(R.string.dial_mood_comedy, 0xFFD65DB1),
    CALM(R.string.dial_mood_calm, 0xFF4CAF7A), // CALM keeps its name so saved folders still work
    EMOTIONAL(R.string.dial_mood_emotional, 0xFF2BB3C0), // scenes that need a very specific feeling
    HOPE(R.string.dial_mood_hope, 0xFFE8B23A),
    FIGHT(R.string.dial_mood_fight, 0xFFE5484D),
    TENSION(R.string.dial_mood_tension, 0xFFE0823D), // in between: tense scene, no fight yet
    SAD(R.string.dial_mood_sad, 0xFF4A7BD0),
}

/** Global music settings (the same for every book): one folder per mood, and the volume. */
@Serializable
data class MusicConfig(
    val folders: Map<String, String> = emptyMap(),
    val volume: Float = 0.25f,
)

object MusicStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileFor(context: Context) = File(context.filesDir, "audiobook_music.json")

    fun load(context: Context): MusicConfig {
        val file = fileFor(context)
        if (!file.exists()) return MusicConfig()
        return runCatching { json.decodeFromString<MusicConfig>(file.readText()) }
            .getOrDefault(MusicConfig())
    }

    fun save(context: Context, config: MusicConfig) {
        runCatching { fileFor(context).writeText(json.encodeToString(config)) }
    }
}

/**
 * Plays random tracks from the folder of the chosen [Mood] under the narration, looping
 * through the folder and cross-fading when the mood changes. Must be used on the main thread.
 */
class BackgroundMusic(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var config by mutableStateOf(MusicStore.load(context))
        private set

    /** The mood that is playing now, or null when the music is off. */
    var mood by mutableStateOf<Mood?>(null)
        private set

    /** A short problem message (missing folder, empty folder...) or null. */
    var message by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var switchJob: Job? = null
    private val lastTrack = HashMap<Mood, Uri>()

    // ---------------------------------------------------------------- settings

    fun folderName(mood: Mood): String? {
        val uri = config.folders[mood.name]?.let(Uri::parse) ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return id.substringAfterLast('/').substringAfterLast(':').ifBlank { id }
    }

    fun setFolder(mood: Mood, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        config = config.copy(folders = config.folders + (mood.name to uri.toString()))
        MusicStore.save(context, config)
        message = null
        lastTrack.remove(mood)
        if (this.mood == mood) restart()
    }

    /** Applies the volume right away; call [commitVolume] when the slider is released. */
    fun setVolume(volume: Float) {
        config = config.copy(volume = volume.coerceIn(0f, 1f))
        runCatching { player?.setVolume(config.volume, config.volume) }
    }

    fun commitVolume() = MusicStore.save(context, config)

    // ---------------------------------------------------------------- playback

    /** Picks a mood. Picking the one that is already playing turns the music off. */
    fun select(newMood: Mood?) = switchTo(if (newMood == mood) null else newMood)

    /** Shows (or clears) a short message under the mood buttons. */
    fun showMessage(text: String?) {
        message = text
    }

    /** Plays exactly [target] (null = off), without the tap-again-to-stop behavior. */
    fun switchTo(target: Mood?) {
        if (target == mood && (target == null || player != null || switchJob?.isActive == true)) return
        mood = target
        message = null
        switchJob?.cancel()
        val old = player
        player = null
        if (old != null) scope.launch { fadeOutAndRelease(old) }
        if (target != null) switchJob = scope.launch { startMood(target) }
    }

    private fun restart() {
        val current = mood ?: return
        mood = null
        select(current)
    }

    private suspend fun startMood(target: Mood) {
        val folder = config.folders[target.name]?.let(Uri::parse)
        if (folder == null) {
            fail(context.getString(R.string.dial_music_pick_folder, context.getString(target.labelRes)))
            return
        }
        val tracks = withContext(Dispatchers.IO) { listAudio(folder) }
        if (tracks.isEmpty()) {
            fail(context.getString(R.string.dial_music_empty, context.getString(target.labelRes)))
            return
        }
        val pick = tracks.filter { it != lastTrack[target] }.ifEmpty { tracks }.random()
        lastTrack[target] = pick
        val mp = try {
            createPlayer(pick)
        } catch (e: Exception) {
            fail(
                context.getString(
                    R.string.dial_music_open_failed,
                    context.getString(target.labelRes),
                    e.message ?: e.javaClass.simpleName,
                ),
            )
            return
        }
        mp.setVolume(0f, 0f)
        mp.setOnCompletionListener {
            // Track ended: free it and play another one from the same folder.
            if (player === mp && mood == target) {
                player = null
                runCatching { mp.release() }
                switchJob = scope.launch { startMood(target) }
            }
        }
        player = mp
        runCatching { mp.start() }
        fade(mp, 0f, config.volume, FADE_IN_MS)
    }

    private fun fail(text: String) {
        message = text
        mood = null
    }

    private suspend fun fade(mp: MediaPlayer, from: Float, to: Float, ms: Long) {
        for (i in 1..FADE_STEPS) {
            if (!scope.isActive) return
            val v = from + (to - from) * i / FADE_STEPS
            runCatching { mp.setVolume(v, v) }
            delay(ms / FADE_STEPS)
        }
    }

    private suspend fun fadeOutAndRelease(mp: MediaPlayer) {
        try {
            fade(mp, config.volume, 0f, FADE_OUT_MS)
        } finally {
            runCatching { mp.release() }
        }
    }

    private suspend fun createPlayer(uri: Uri): MediaPlayer = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        cont.invokeOnCancellation { runCatching { mp.release() } }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

    /** Audio files inside the chosen folder (and up to two levels of subfolders). */
    private fun listAudio(tree: Uri): List<Uri> {
        val result = mutableListOf<Uri>()
        fun walk(parentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            runCatching {
                context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val mime = c.getString(1).orEmpty()
                        val name = c.getString(2).orEmpty().lowercase()
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                                if (depth < 2) walk(id, depth + 1)
                            mime.startsWith("audio/") || AUDIO_EXTENSIONS.any { name.endsWith(it) } ->
                                result += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return result
    }

    /** Call when the dial is closed. */
    fun release() {
        switchJob?.cancel()
        runCatching { player?.release() }
        player = null
        mood = null
    }

    companion object {
        private const val FADE_STEPS = 20
        private const val FADE_IN_MS = 1500L
        private const val FADE_OUT_MS = 700L
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".ogg", ".opus", ".flac", ".wav", ".aac")
    }
}
