package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Loads a book/manga/anime cover as JPEG bytes for the Anki `{cover}` marker. */
object CoverImage {
    private const val MAX_HEIGHT = 700

    @Volatile
    private var last: Pair<String, ByteArray>? = null

    /** [data] is whatever Coil already knows how to load (a Manga or Anime, a File, a URL...). */
    suspend fun load(context: Context, key: String, data: Any): ByteArray? {
        last?.takeIf { it.first == key }?.let { return it.second }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context.applicationContext)
                    .data(data)
                    .size(Size.ORIGINAL)
                    .build()
                val bitmap = context.applicationContext.imageLoader.execute(request).image
                    ?.asDrawable(context.resources)
                    ?.getBitmapOrNull()
                bitmap?.let(::toJpeg)
            }.getOrNull()
        }
        if (bytes != null) last = key to bytes
        return bytes
    }

    suspend fun loadFile(key: String, file: File): ByteArray? {
        last?.takeIf { it.first == key }?.let { return it.second }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.let(::toJpeg)
            }.getOrNull()
        }
        if (bytes != null) last = key to bytes
        return bytes
    }

    private fun toJpeg(source: Bitmap): ByteArray {
        val bitmap = if (source.height > MAX_HEIGHT) {
            val scale = MAX_HEIGHT.toFloat() / source.height
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), MAX_HEIGHT, true)
        } else {
            source
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
