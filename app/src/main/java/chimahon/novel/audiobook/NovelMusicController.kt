package chimahon.novel.audiobook

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Immersive background music for the EPUB reader. Same moods, dial and PC-AI export/import as the
 * audiobook, but the "lines" are the paragraphs of the current chapter: Auto works one chapter at
 * a time (the analysis is saved per chapter) and follows the reading progress.
 */
class NovelMusicController(
    private val context: Context,
    private val bookKey: String,
    private val scope: CoroutineScope,
    private val paragraphsOf: suspend (Int) -> List<String>,
) : MoodDialHost {

    override val music = BackgroundMusic(context, scope)

    override var autoMusic by mutableStateOf(false)
        private set

    var chapter by mutableIntStateOf(-1)
        private set

    var paragraphCount by mutableIntStateOf(0)
        private set

    private var loadedChapter = -1
    private var cumulative = IntArray(0)
    private var job: Job? = null

    val auto = AutoMood(context, scope) { labels -> saveLabels(labels) }

    /** The reader moved to another chapter: load its paragraphs and its saved analysis. */
    fun openChapter(index: Int) {
        if (index == loadedChapter) return
        job?.cancel()
        job = scope.launch {
            val paragraphs = runCatching { paragraphsOf(index) }.getOrDefault(emptyList())
            loadedChapter = index
            chapter = index
            paragraphCount = paragraphs.size
            var total = 0
            cumulative = IntArray(paragraphs.size) { i ->
                total += paragraphs[i].length.coerceAtLeast(1)
                total
            }
            val lines = paragraphs.mapIndexed { i, text -> SrtLine(i, 0L, 0L, text) }
            val saved = withContext(Dispatchers.IO) { loadLabels(index) }
            auto.attach(lines, saved)
            applyAuto(lastProgress)
        }
    }

    private var lastProgress = 0.0

    /** Reading progress inside the chapter (0..1). */
    fun onProgress(progress: Double) {
        lastProgress = progress
        applyAuto(progress)
    }

    private fun applyAuto(progress: Double) {
        if (!autoMusic || cumulative.isEmpty()) return
        val target = (progress.coerceIn(0.0, 1.0) * cumulative.last()).toInt()
        var line = cumulative.indexOfFirst { it >= target }
        if (line < 0) line = cumulative.lastIndex
        auto.moodAt(line)?.let { music.switchTo(it) }
    }

    override fun toggleAuto() {
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
        applyAuto(lastProgress)
    }

    override fun pickMood(mood: Mood) {
        autoMusic = false
        music.select(mood)
    }

    fun clearAnalysis() {
        autoMusic = false
        auto.clearResults()
    }

    /** Stops the music (the audiobook dial takes over, or the reader closes). */
    fun stop() {
        autoMusic = false
        music.release()
    }

    fun release() {
        job?.cancel()
        auto.cancel()
        music.release()
    }

    // ---------------------------------------------------------------- saved analysis (per chapter)

    private fun file(index: Int): File {
        val safe = bookKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(File(context.filesDir, "novel_music").apply { mkdirs() }, "${safe}_$index.json")
    }

    private fun saveLabels(labels: List<String>) {
        val index = loadedChapter
        if (index < 0) return
        scope.launch(Dispatchers.IO) {
            runCatching { file(index).writeText(JSONArray(labels).toString()) }
        }
    }

    private fun loadLabels(index: Int): List<String> = runCatching {
        val array = JSONArray(file(index).readText())
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())
}
