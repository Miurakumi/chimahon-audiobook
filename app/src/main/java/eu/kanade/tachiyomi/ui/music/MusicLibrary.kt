package eu.kanade.tachiyomi.ui.music

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One song found in a music folder. */
data class MusicTrack(
    val uri: Uri,
    /** File name without the extension. */
    val title: String,
)

/** A music folder picked by the user, with the songs found inside it. */
data class MusicFolder(
    val uri: Uri,
    val name: String,
    val tracks: List<MusicTrack>,
)

/** Remembers which folders the user added (just their addresses, no music is copied). */
object MusicFolderStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(context: Context) = File(context.filesDir, "music_folders.json")

    fun load(context: Context): List<Uri> {
        val file = fileFor(context)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(file.readText()) }
            .getOrDefault(emptyList())
            .map(Uri::parse)
    }

    fun save(context: Context, folders: List<Uri>) {
        runCatching { fileFor(context).writeText(json.encodeToString(folders.map(Uri::toString))) }
    }

    fun keepAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun releaseAccess(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

object MusicScanner {

    private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".ogg", ".opus", ".flac", ".wav", ".aac", ".wma")
    private const val MAX_DEPTH = 3

    /** Reads the folder (and its subfolders) and returns the songs sorted by name. */
    suspend fun scan(context: Context, tree: Uri): MusicFolder = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
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
                        val name = c.getString(2).orEmpty()
                        val lower = name.lowercase()
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                                if (depth < MAX_DEPTH) walk(id, depth + 1)
                            mime.startsWith("audio/") || AUDIO_EXTENSIONS.any { lower.endsWith(it) } ->
                                tracks += MusicTrack(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                    title = name.substringBeforeLast('.'),
                                )
                        }
                    }
                }
            }
        }
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        if (rootId != null) walk(rootId, 0)
        val folderName = rootId?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { rootId } ?: tree.toString()
        MusicFolder(
            uri = tree,
            name = folderName,
            tracks = tracks.sortedBy { it.title.lowercase() },
        )
    }
}

/**
 * The player of the Music tab. It lives for the whole app (not inside the screen), so the
 * song keeps playing while you move to another tab. Use only from the main thread.
 */
object MusicPlayer {

    private var player: MediaPlayer? = null
    private var queue: List<MusicTrack> = emptyList()

    var current by mutableStateOf<MusicTrack?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Plays [track]; when it ends, the next one of [list] plays. */
    fun play(context: Context, track: MusicTrack, list: List<MusicTrack>) {
        queue = list
        start(context.applicationContext, track)
    }

    private fun start(context: Context, track: MusicTrack) {
        stopPlayer()
        error = null
        current = track
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            mp.setDataSource(context, track.uri)
            mp.setOnPreparedListener {
                if (player === mp) {
                    it.start()
                    isPlaying = true
                }
            }
            mp.setOnCompletionListener { if (player === mp) next(context) }
            mp.setOnErrorListener { _, what, extra ->
                if (player === mp) {
                    error = "$what/$extra"
                    isPlaying = false
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            player = null
            isPlaying = false
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
        }
    }

    fun next(context: Context) = step(context, 1)

    fun previous(context: Context) = step(context, -1)

    private fun step(context: Context, delta: Int) {
        val index = queue.indexOfFirst { it.uri == current?.uri }
        if (index < 0) return
        val target = queue.getOrNull(index + delta)
        if (target == null) {
            // End of the list: stop.
            if (delta > 0) stop()
            return
        }
        start(context.applicationContext, target)
    }

    val positionMs: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    val durationMs: Int
        get() = runCatching { player?.duration?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
    }

    fun stop() {
        stopPlayer()
        current = null
        isPlaying = false
    }

    private fun stopPlayer() {
        val mp = player
        player = null
        runCatching { mp?.release() }
        isPlaying = false
    }
}
