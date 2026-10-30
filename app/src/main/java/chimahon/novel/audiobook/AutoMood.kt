package chimahon.novel.audiobook

import eu.kanade.tachiyomi.R
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A group of consecutive subtitle lines that gets one mood from the model. */
data class MoodChunk(
    /** First line index of the chunk. */
    val start: Int,
    /** Last line index of the chunk, inclusive. */
    val end: Int,
    /** The text sent to the model. */
    val text: String,
)

/**
 * The small on-device model (Qwen3 0.6B) used to detect moods. This is the official LiteRT
 * community file, whose tokenizer was fixed on 2026-10-13 (older copies crash newer runtimes).
 */
object MoodModel {

    private const val FILE_NAME = "Qwen3-0.6B.litertlm"
    private const val URL_STRING =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/$FILE_NAME"

    /** The real file is about 614 MB; anything much smaller is a broken download. */
    private const val MIN_SIZE_BYTES = 400_000_000L

    private fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context) = File(dir(context), FILE_NAME)

    /** Written just before the model is opened and removed right after: if it is still there on
     *  the next start, the app was killed while opening the model. */
    fun guardFile(context: Context) = File(dir(context), "engine_guard")

    /** Deletes models from older versions of the app (they took space and no longer work). */
    private fun deleteOldModels(context: Context) {
        dir(context).listFiles()?.forEach {
            if (it.name.endsWith(".litertlm") && it.name != FILE_NAME) it.delete()
        }
    }

    fun isReady(context: Context): Boolean {
        deleteOldModels(context)
        return file(context).let { it.isFile && it.length() > MIN_SIZE_BYTES }
    }

    /** Downloads the model. Throws on failure; [onProgress] gets 0f..1f (or -1f when unknown). */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(context)
        val part = File(target.parentFile, "$FILE_NAME.part")
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) {
                throw IllegalStateException(context.getString(R.string.dial_model_incomplete))
            }
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException(context.getString(R.string.dial_err_save_model))
            onProgress(1f)
        } finally {
            connection.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }

    /** Copies a model file the user picked by hand (fallback when the download is refused). */
    suspend fun importFrom(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val target = file(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException(context.getString(R.string.dial_err_read_file))
    }
}

/**
 * Detects the mood of a book from its subtitles with a small on-device model, and answers
 * "which mood is playing at line N". The result per chunk is saved with the book, so the
 * analysis can be stopped and continued, and runs only once per SRT.
 *
 * Must be used on the main thread (compose state); the model itself runs on a background thread.
 */
class AutoMood(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLabelsChanged: (List<String>) -> Unit,
) {
    /** One entry per chunk: "" = not analyzed yet, "-" = no clear mood, else a [Mood] name. */
    var labels by mutableStateOf<List<String>>(emptyList())
        private set
    var chunks by mutableStateOf<List<MoodChunk>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var modelReady by mutableStateOf(MoodModel.isReady(context))
        private set

    /** Download progress 0f..1f, -1f when the size is unknown, null when not downloading. */
    var downloadProgress by mutableStateOf<Float?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var secondsPerChunk by mutableStateOf<Float?>(null)
        private set

    /** A normal (non-error) message, e.g. "file saved". */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Every subtitle line of the book (one per line, same numbering as the dial counter). */
    private var sourceLines: List<String> = emptyList()

    private var effective by mutableStateOf<List<Mood?>>(emptyList())
    private var job: Job? = null
    private var savedLabels: List<String> = emptyList()

    /** Size of the model file in MB, for the "Model ready" line. */
    val modelSizeMb: Int get() = (MoodModel.file(context).length() / 1_000_000L).toInt()

    init {
        // The app was killed while opening the model last time: say so instead of failing silently.
        val guard = MoodModel.guardFile(context)
        if (guard.exists()) {
            guard.delete()
            status = context.getString(R.string.dial_model_crashed)
        }
    }

    val analyzedCount: Int get() = labels.count { it.isNotEmpty() }

    /** True when at least one chunk has a usable mood. */
    val hasResults: Boolean get() = effective.any { it != null }

    /** Called when the book's lines are loaded. [saved] are the labels stored for this book. */
    fun attach(lines: List<SrtLine>, saved: List<String>) {
        cancel()
        sourceLines = lines.map { it.text.replace('\n', ' ').trim() }
        chunks = buildChunks(lines)
        labels = if (saved.size == chunks.size) saved else List(chunks.size) { "" }
        savedLabels = labels
        recompute()
    }

    /** Forget everything (the SRT changed). */
    fun reset() {
        cancel()
        chunks = emptyList()
        sourceLines = emptyList()
        labels = emptyList()
        effective = emptyList()
        savedLabels = emptyList()
        status = null
        notice = null
    }

    /** Throws away every detected mood so the book can be analyzed again from the start. */
    fun clearResults() {
        cancel()
        labels = List(chunks.size) { "" }
        secondsPerChunk = null
        status = null
        recompute()
        save()
    }

    fun cancel() {
        job?.cancel()
        job = null
        running = false
    }

    // ---------------------------------------------------------------- model files

    fun downloadModel() {
        if (downloadProgress != null) return
        status = null
        downloadProgress = -1f
        scope.launch {
            try {
                MoodModel.download(context) { downloadProgress = it }
                modelReady = MoodModel.isReady(context)
                if (!modelReady) status = context.getString(R.string.dial_model_incomplete)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                status = if (reason.startsWith("HTTP 401") || reason.startsWith("HTTP 403")) {
                    context.getString(R.string.dial_hf_login, reason)
                } else {
                    context.getString(R.string.dial_model_download_failed, reason)
                }
            } finally {
                downloadProgress = null
            }
        }
    }

    fun importModel(uri: Uri) {
        status = null
        downloadProgress = -1f
        scope.launch {
            try {
                MoodModel.importFrom(context, uri)
                modelReady = MoodModel.isReady(context)
                if (!modelReady) status = context.getString(R.string.dial_model_not_valid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = context.getString(R.string.dial_model_copy_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                downloadProgress = null
            }
        }
    }

    // ---------------------------------------------------------------- PC AI file (export / import)

    /** The whole book as numbered lines, with the instructions a bigger AI on a PC needs. */
    fun exportText(): String = buildString {
        append(EXPORT_PROMPT.replace("%N", sourceLines.size.toString()))
        append("\n\n=== TEXT ===\n")
        sourceLines.forEachIndexed { i, text -> append(i + 1).append(" | ").append(text).append('\n') }
    }

    /** Saves the export file where the user chose. */
    fun exportTo(uri: Uri) {
        notice = null
        if (sourceLines.isEmpty()) {
            status = context.getString(R.string.dial_load_files_first)
            return
        }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(exportText().toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException(context.getString(R.string.dial_err_save_model))
            status = null
            notice = context.getString(R.string.dial_exported)
        } catch (e: Exception) {
            status = context.getString(R.string.dial_export_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Reads the AI's answer ("1-34 DAILY" lines) and sets the mood of every part it covers. */
    fun importFrom(uri: Uri) {
        notice = null
        if (chunks.isEmpty()) {
            status = context.getString(R.string.dial_load_files_first)
            return
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val ranges = RANGE_LINE.findAll(text).mapNotNull { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val to = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val mood = parseMood(m.groupValues[3]) ?: return@mapNotNull null
            Triple(minOf(from, to), maxOf(from, to), mood)
        }.toList()
        if (ranges.isEmpty()) {
            status = context.getString(R.string.dial_import_empty)
            return
        }
        val updated = labels.toMutableList()
        for (i in chunks.indices) {
            val line = chunks[i].start + 1
            ranges.lastOrNull { line in it.first..it.second }?.let { updated[i] = it.third.name }
        }
        labels = updated
        status = null
        recompute()
        save()
        notice = context.getString(R.string.dial_imported, ranges.size)
    }

    // ---------------------------------------------------------------- analysis

    /** Analyzes the next [count] chunks that have no label yet (null = all of them). */
    fun analyze(count: Int?) {
        if (running || chunks.isEmpty()) return
        if (!MoodModel.isReady(context)) {
            status = context.getString(R.string.dial_download_first)
            return
        }
        val pending = chunks.indices.filter { labels[it].isEmpty() }.let { if (count != null) it.take(count) else it }
        if (pending.isEmpty()) {
            status = context.getString(R.string.dial_all_analyzed)
            return
        }
        status = null
        running = true
        job = scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val config = EngineConfig(
                        modelPath = MoodModel.file(context).absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.path,
                    )
                    Engine(config).use { engine ->
                        val guard = MoodModel.guardFile(context)
                        guard.writeText("1")
                        try {
                            engine.initialize()
                        } finally {
                            guard.delete()
                        }
                        var sinceSave = 0
                        for (i in pending) {
                            ensureActive()
                            val started = System.currentTimeMillis()
                            val previous = chunks.getOrNull(i - 1)?.text?.takeLast(PREVIOUS_CHARS).orEmpty()
                            val message = "PREVIOUS:\n" + previous.ifBlank { "(start of the book)" } +
                                "\n\nCURRENT:\n" + chunks[i].text + "\n/no_think"
                            val reply = engine.createConversation(
                                ConversationConfig(systemInstruction = Contents.of(SYSTEM_PROMPT)),
                            ).use { conversation ->
                                val out = StringBuilder()
                                conversation.sendMessageAsync(message).collect { out.append(it.toString()) }
                                out.toString()
                            }
                            val seconds = (System.currentTimeMillis() - started) / 1000f
                            val label = (refine(parseMood(reply) ?: Mood.CALM, chunks[i].text) ?: Mood.CALM).name
                            android.util.Log.d("AutoMood", "chunk $i reply=\"${reply.trim().take(200).replace("\n", " | ")}\" -> $label")
                            withContext(Dispatchers.Main) {
                                labels = labels.toMutableList().also { it[i] = label }
                                secondsPerChunk = secondsPerChunk?.let { it * 0.7f + seconds * 0.3f } ?: seconds
                                recompute()
                            }
                            if (++sinceSave >= SAVE_EVERY) {
                                sinceSave = 0
                                withContext(Dispatchers.Main) { save() }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = context.getString(R.string.dial_analysis_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                running = false
                save()
            }
        }
    }

    private fun save() {
        if (labels == savedLabels) return
        savedLabels = labels
        onLabelsChanged(labels)
    }

    // ---------------------------------------------------------------- query

    /** The mood at [lineIndex], or null when that part of the book has not been analyzed. */
    fun moodAt(lineIndex: Int): Mood? {
        if (chunks.isEmpty() || effective.isEmpty()) return null
        var low = 0
        var high = chunks.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (chunks[mid].start <= lineIndex) low = mid else high = mid - 1
        }
        return effective.getOrNull(low)
    }

    /** Label shown in the preview list for chunk [index]. */
    fun labelOf(index: Int): Mood? = labels.getOrNull(index)?.let { name -> Mood.entries.firstOrNull { it.name == name } }

    // ---------------------------------------------------------------- internals

    /** Turns raw labels into the mood used while playing: fills gaps and drops one-chunk blips. */
    private fun recompute() {
        val result = arrayOfNulls<Mood>(labels.size)
        for (i in labels.indices) {
            result[i] = when (val label = labels[i]) {
                "" -> null
                "-" -> result.getOrNull(i - 1)
                else -> Mood.entries.firstOrNull { it.name == label }
            }
        }
        for (i in 1 until result.size - 1) {
            if (result[i] != null && result[i] != result[i - 1] && result[i - 1] != null && result[i - 1] == result[i + 1]) {
                result[i] = result[i - 1]
            }
        }
        effective = result.toList()
    }

    private fun buildChunks(lines: List<SrtLine>): List<MoodChunk> {
        val result = mutableListOf<MoodChunk>()
        var start = 0
        val text = StringBuilder()
        var count = 0
        fun flush(endIndex: Int) {
            if (count > 0) result += MoodChunk(start, endIndex, text.toString().take(MAX_CHUNK_CHARS))
            text.clear()
            count = 0
        }
        for ((i, line) in lines.withIndex()) {
            if (count == 0) start = i
            if (text.isNotEmpty()) text.append('\n')
            text.append(line.text)
            count++
            if (count >= MAX_CHUNK_LINES || text.length >= TARGET_CHUNK_CHARS) flush(i)
        }
        flush(lines.lastIndex)
        return result
    }

    /** Finds the first mood word in the model's reply (ignoring any <think> block). */
    private fun parseMood(reply: String): Mood? {
        val noThink = reply.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").uppercase()
        // The model writes a short summary first and the answer after "MOOD:".
        val marker = noThink.lastIndexOf("MOOD:")
        val clean = if (marker >= 0) noThink.substring(marker + 5) else noThink
        val found = KEYWORDS.mapNotNull { (word, mood) ->
            clean.indexOf(word).takeIf { it >= 0 }?.let { it to mood }
        }
        return found.minByOrNull { it.first }?.second
    }

    /**
     * A small model over-picks FIGHT in adventure novels (guild, monsters, swords are just
     * scenery there). So FIGHT is only kept when the text itself shows combat; with weaker
     * signs it becomes TENSION.
     */
    private fun refine(mood: Mood?, text: String): Mood? {
        val clean = text.replace("素敵", "") // "lovely" contains the character for "enemy"
        return when (mood) {
            Mood.FIGHT -> {
                val cues = COMBAT_CUES.count { clean.contains(it) }
                val exclamations = clean.count { it == '！' || it == '!' }
                when {
                    cues >= 2 || (cues >= 1 && exclamations >= 3) -> Mood.FIGHT
                    else -> Mood.TENSION
                }
            }
            // Same idea for comedy: the model over-picks it, so it needs signs in the text.
            Mood.COMEDY -> {
                val cues = COMEDY_CUES.count { clean.contains(it) }
                val surprised = clean.contains("？！") || clean.contains("！？") || clean.contains("?!")
                if (cues >= 1 || surprised) Mood.COMEDY else Mood.CALM
            }
            else -> mood
        }
    }

    companion object {
        /** Words that show real combat (not just fantasy scenery). Tune here if it misjudges. */
        private val COMBAT_CUES = listOf(
            "斬", "殴", "蹴", "攻撃", "剣を", "刃", "血", "叫", "倒す", "倒し", "倒さ", "敵",
            "魔法", "詠唱", "爆", "衝撃", "咆哮", "突進", "悲鳴", "戦闘", "戦い", "振るっ", "避け",
        )

        /** Words that show a joke or comic reaction. A plain smile (笑った) alone is not enough. */
        private val COMEDY_CUES = listOf(
            "笑", "爆笑", "ツッコミ", "恥ずかし", "慌て", "あはは", "えへへ", "ふふ", "ははは",
            "冗談", "照れ", "赤面", "顔を赤",
        )

        private const val MAX_CHUNK_LINES = 12
        private const val TARGET_CHUNK_CHARS = 260
        private const val MAX_CHUNK_CHARS = 320
        private const val SAVE_EVERY = 3
        private const val PREVIOUS_CHARS = 160

        /** "1-34 DAILY", "**35 - 60**: FIGHT"... (markdown and extra words are tolerated). */
        private val RANGE_LINE = Regex("""(\d+)\s*[-–—]\s*(\d+)[^A-Za-zÀ-ÿ\n]*([A-Za-zÀ-ÿ_ ]+)""")

        /** Instructions placed at the top of the file for the PC AI. %N = number of lines. */
        private const val EXPORT_PROMPT =
            "TASK: I am listening to a Japanese audiobook and want background music that follows the scenes. " +
                "Below, after '=== TEXT ===', the book is split into %N numbered lines (format: number | text). " +
                "Read it, split it into scenes, and decide the mood of each scene by its overall feeling (what is happening, " +
                "how the characters feel, what came before), not by single words.\n\n" +
                "ANSWER FORMAT: only lines like  START-END MOOD  (line numbers, then one mood word). " +
                "Example:\n1-34 DAILY\n35-52 TENSION\n53-80 FIGHT\n" +
                "Cover every line from 1 to %N without gaps or overlaps. No explanations, no extra text. " +
                "If the text is sent in parts, answer only for the numbers of that part.\n\n" +
                "MOODS (use exactly these words):\n" +
                "DAILY: ordinary talk, walking, eating, shopping, explaining, planning, describing places.\n" +
                "COMEDY: jokes, teasing, silly misunderstandings, comic overreactions.\n" +
                "EMOTIONAL: a moment that needs a very specific feeling: tender, touching, romantic, nostalgic, a heartfelt confession.\n" +
                "HOPE: determination, promises, joy, a new goal, warm encouragement.\n" +
                "SAD: grief, loss, regret, loneliness, crying.\n" +
                "TENSION: danger, fear, secrets or a threat is felt, but nobody is fighting yet.\n" +
                "FIGHT: characters are hitting, slashing, casting attacks or being attacked right now.\n" +
                "Fantasy scenery (guild, dungeon, monsters, weapons, magic) is NOT fighting by itself. " +
                "Pick DAILY only if nothing else fits."

        /** Words the model may answer with (including near-synonyms) and the mood they mean. */
        private val KEYWORDS = listOf(
            "FIGHT" to Mood.FIGHT, "BATTLE" to Mood.FIGHT, "COMBAT" to Mood.FIGHT,
            "TENSION" to Mood.TENSION, "TENSE" to Mood.TENSION, "SUSPENSE" to Mood.TENSION,
            "DAILY" to Mood.CALM, "EVERYDAY" to Mood.CALM, "ORDINARY" to Mood.CALM,
            "NORMAL" to Mood.CALM, "CALM" to Mood.CALM, "PEACEFUL" to Mood.CALM,
            "SAD" to Mood.SAD, "GRIEF" to Mood.SAD, "SORROW" to Mood.SAD,
            "HOPE" to Mood.HOPE, "JOY" to Mood.HOPE, "DETERMINATION" to Mood.HOPE,
            "COMEDY" to Mood.COMEDY, "FUNNY" to Mood.COMEDY, "HUMOR" to Mood.COMEDY,
            "HUMOUR" to Mood.COMEDY, "JOKE" to Mood.COMEDY, "COMIC" to Mood.COMEDY,
            "EMOTIONAL" to Mood.EMOTIONAL, "EMOTION" to Mood.EMOTIONAL, "TOUCHING" to Mood.EMOTIONAL,
            "MOVING" to Mood.EMOTIONAL, "HEARTFELT" to Mood.EMOTIONAL, "ROMANTIC" to Mood.EMOTIONAL,
            "NOSTALGI" to Mood.EMOTIONAL, "TENDER" to Mood.EMOTIONAL,
            "PELEA" to Mood.FIGHT, "TENSIÓN" to Mood.TENSION, "DÍA A DÍA" to Mood.CALM,
            "DIA A DIA" to Mood.CALM, "TRISTEZA" to Mood.SAD, "TRISTE" to Mood.SAD,
            "ESPERANZA" to Mood.HOPE, "COMEDIA" to Mood.COMEDY, "EMOTIVO" to Mood.EMOTIONAL,
            "EMOCIONAL" to Mood.EMOTIONAL,
        )

        private const val SYSTEM_PROMPT =
            "You choose the background music for a Japanese novel. You get PREVIOUS (the text just before, " +
                "for context only) and CURRENT (the passage to judge). " +
                "Step 1: in ONE short English sentence, summarize what is happening in CURRENT and how the " +
                "atmosphere continues or changes from PREVIOUS. " +
                "Step 2: on a new line write MOOD: followed by exactly one of these words. " +
                "DAILY: ordinary talk, walking, eating, shopping, explaining, planning, describing places. " +
                "COMEDY: jokes, teasing, silly misunderstandings, comic overreactions. " +
                "EMOTIONAL: a moment that needs a very specific feeling: tender, touching, romantic, nostalgic, a heartfelt confession. " +
                "HOPE: determination, promises, joy, a new goal, warm encouragement. " +
                "SAD: grief, loss, regret, loneliness, crying. " +
                "TENSION: danger, fear, secrets or a threat is felt, but nobody is fighting yet. " +
                "FIGHT: characters are hitting, slashing, casting attacks or being attacked right now. " +
                "Fantasy scenery (guild, dungeon, monsters, weapons, magic) is NOT fighting by itself. " +
                "Judge the feeling of the scene, not single words. Pick DAILY only if nothing else fits."
    }
}
