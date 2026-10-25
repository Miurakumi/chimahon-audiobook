package chimahon.novel.audiobook

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** How lines react when the dial lands on them. */
enum class PlayMode {
    /** Every line plays when the dial stops on it. */
    ALL,

    /** Only dialogue lines (starting with a quote bracket) play. */
    QUOTES,

    /** Nothing plays by itself; tap the play button to hear the line. */
    MANUAL,
}

/** Everything remembered about a book's audiobook. Stored as one small JSON file. */
@Serializable
data class AudiobookState(
    val audioUri: String? = null,
    val srtUri: String? = null,
    val audioName: String? = null,
    val srtName: String? = null,
    val lineIndex: Int = 0,
    val mode: String = PlayMode.ALL.name,
    val speed: Float = 1f,
)

object AudiobookStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileFor(context: Context, bookKey: String): File {
        val dir = File(context.filesDir, "audiobook").apply { mkdirs() }
        val safe = bookKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "book" }
        return File(dir, "$safe.json")
    }

    fun load(context: Context, bookKey: String): AudiobookState {
        val file = fileFor(context, bookKey)
        if (!file.exists()) return AudiobookState()
        return runCatching { json.decodeFromString<AudiobookState>(file.readText()) }
            .getOrDefault(AudiobookState())
    }

    fun save(context: Context, bookKey: String, state: AudiobookState) {
        runCatching {
            val file = fileFor(context, bookKey)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
