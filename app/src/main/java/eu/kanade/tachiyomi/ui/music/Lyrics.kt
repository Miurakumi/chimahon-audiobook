package eu.kanade.tachiyomi.ui.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** What we know about a song file (from its tags, with the file name as a fallback). */
data class SongInfo(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /** Album art (JPEG/PNG bytes), used as the Anki card cover. */
    val cover: ByteArray?,
    /** True when [title] and [artist] came from the file tags (not guessed from the name). */
    val tagged: Boolean,
)

/** Turns lyrics in any common shape (LRC with times, SRT, plain text) into SRT text. */
object LrcConverter {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Returns SRT text, or null when [raw] has no usable lines. */
    fun toSrt(raw: String, durationMs: Long): String? {
        val text = raw.trimStart('﻿').replace("\r\n", "\n").replace('\r', '\n')
        if (text.contains("-->")) return text // already an SRT

        val timed = mutableListOf<Pair<Long, String>>()
        for (line in text.lines()) {
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val content = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].toLong()
                val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLong()
                timed += (minutes * 60_000 + seconds * 1000 + fraction) to content
            }
        }

        val result = StringBuilder()
        var index = 0
        fun add(start: Long, end: Long, content: String) {
            index++
            result.append(index).append('\n')
                .append(clock(start)).append(" --> ").append(clock(end)).append('\n')
                .append(content).append("\n\n")
        }

        if (timed.isNotEmpty()) {
            val sorted = timed.sortedBy { it.first }
            for ((i, entry) in sorted.withIndex()) {
                if (entry.second.isBlank()) continue
                val start = entry.first
                val next = sorted.drop(i + 1).firstOrNull { it.first > start }?.first
                var end = next ?: (if (durationMs > start) durationMs else start + 5000)
                end = minOf(end, start + 20_000)
                if (end <= start + 300) end = start + 1500
                add(start, end, entry.second)
            }
        } else {
            // No times at all: spread the lines over the song (rough, but readable and tappable).
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
            if (lines.isEmpty()) return null
            val span = if (durationMs > 0) durationMs else lines.size * 4000L
            for ((i, content) in lines.withIndex()) {
                add(span * i / lines.size, span * (i + 1) / lines.size, content)
            }
        }
        return if (index == 0) null else result.toString()
    }

    private fun clock(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }
}

/** Free lyrics database (https://lrclib.net): no account, no key. */
object LrcLib {

    private const val BASE = "https://lrclib.net/api/"
    private const val USER_AGENT = "ChimahonDial/1.0 (https://github.com/Miurakumi/chimahon-audiobook)"

    /** Lyrics text (with times when available) or null when nothing was found. */
    suspend fun find(info: SongInfo, cleanedName: String): String? = withContext(Dispatchers.IO) {
        val seconds = (info.durationMs / 1000).toInt()
        runCatching {
            // 1) Exact match when the file has title + artist tags.
            if (info.tagged && !info.artist.isNullOrBlank()) {
                val url = BASE + "get?track_name=${enc(info.title)}&artist_name=${enc(info.artist)}" +
                    (info.album?.takeIf { it.isNotBlank() }?.let { "&album_name=${enc(it)}" } ?: "") +
                    (if (seconds > 0) "&duration=$seconds" else "")
                request(url)?.let { body ->
                    pick(JSONObject(body))?.let { return@runCatching it }
                }
            }
            // 2) Search by tags, then by the file name.
            val searches = buildList {
                if (info.tagged && !info.artist.isNullOrBlank()) {
                    add(BASE + "search?track_name=${enc(info.title)}&artist_name=${enc(info.artist)}")
                }
                add(BASE + "search?q=${enc(cleanedName)}")
                if (info.tagged) add(BASE + "search?q=${enc(info.title)}")
            }
            for (url in searches) {
                val body = request(url) ?: continue
                val results = JSONArray(body)
                val candidates = (0 until results.length()).map { results.getJSONObject(it) }
                val ordered = if (seconds > 0) {
                    candidates.sortedBy { abs(it.optDouble("duration", 0.0) - seconds) }
                } else {
                    candidates
                }
                ordered.firstNotNullOfOrNull { pick(it, requireSynced = true) }?.let { return@runCatching it }
                ordered.firstNotNullOfOrNull { pick(it) }?.let { return@runCatching it }
            }
            null
        }.getOrNull()
    }

    private fun pick(o: JSONObject, requireSynced: Boolean = false): String? {
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
        if (synced.isNotBlank()) return synced
        if (requireSynced) return null
        val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
        return plain.takeIf { it.isNotBlank() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

object LyricsResolver {

    sealed interface Result {
        data class Found(val srt: File, val source: String) : Result
        data object NotFound : Result
    }

    fun cacheFile(context: Context, key: String) = File(File(context.filesDir, "lyrics").apply { mkdirs() }, "$key.srt")

    /** Reads the tags, album art and length of the song. Call off the main thread. */
    fun readInfo(context: Context, uri: Uri, fileTitle: String): SongInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val tagTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val tagArtist = (
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                )?.takeIf { it.isNotBlank() }
            SongInfo(
                title = tagTitle ?: fileTitle,
                artist = tagArtist,
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                cover = runCatching { retriever.embeddedPicture }.getOrNull(),
                tagged = tagTitle != null && tagArtist != null,
            )
        } catch (e: Exception) {
            SongInfo(fileTitle, null, null, 0L, null, tagged = false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Removes "(Official Video)", "[HD]", leading track numbers and similar noise from a file name. */
    fun cleanName(name: String): String = name
        .replace(Regex("""\([^)]*\)|\[[^\]]*]|【[^】]*】"""), " ")
        .replace(Regex("""^\s*\d{1,3}\s*[.\-_)]\s*"""), "")
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Cached lyrics, then a .lrc/.srt next to the song, then LRCLIB on the internet. */
    suspend fun resolve(context: Context, uri: Uri, info: SongInfo, fileTitle: String, key: String): Result {
        val cache = cacheFile(context, key)
        if (cache.isFile && cache.length() > 0) return Result.Found(cache, "cache")

        val sidecar = withContext(Dispatchers.IO) { readSidecar(context, uri) }
        sidecar?.let { text ->
            LrcConverter.toSrt(text, info.durationMs)?.let {
                cache.writeText(it)
                return Result.Found(cache, "file")
            }
        }

        val online = LrcLib.find(info, cleanName(fileTitle))
        online?.let { text ->
            LrcConverter.toSrt(text, info.durationMs)?.let {
                withContext(Dispatchers.IO) { cache.writeText(it) }
                return Result.Found(cache, "online")
            }
        }
        return Result.NotFound
    }

    /** Converts a lyrics file picked by the user and stores it for this song. */
    suspend fun importFile(context: Context, picked: Uri, info: SongInfo, key: String): Result =
        withContext(Dispatchers.IO) {
            val text = runCatching { readText(context, picked) }.getOrNull()
            val srt = text?.let { LrcConverter.toSrt(it, info.durationMs) }
            if (srt == null) {
                Result.NotFound
            } else {
                val cache = cacheFile(context, key)
                cache.writeText(srt)
                Result.Found(cache, "file")
            }
        }

    private fun readSidecar(context: Context, song: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(song) }.getOrNull() ?: return null
        val base = id.substringBeforeLast('.', id)
        for (ext in listOf("lrc", "srt")) {
            val sibling = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(song, "$base.$ext")
            }.getOrNull() ?: continue
            runCatching { readText(context, sibling) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("unreadable")
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }
}
