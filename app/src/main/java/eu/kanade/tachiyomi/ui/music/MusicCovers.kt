package eu.kanade.tachiyomi.ui.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Album art helpers: folder covers for the Music tab and the color of the lyrics player. */
object MusicCovers {

    private val folderCache = ConcurrentHashMap<String, Optional<ImageBitmap>>()

    /** Cover of a folder: the album art of the first song that has one (null if none does). */
    suspend fun forFolder(context: Context, folder: MusicFolder): ImageBitmap? {
        val key = folder.uri.toString()
        folderCache[key]?.let { return it.orElse(null) }
        val result = withContext(Dispatchers.IO) {
            folder.tracks.take(8).firstNotNullOfOrNull { embedded(context, it.uri) }
        }?.asImageBitmap()
        folderCache[key] = Optional.ofNullable(result)
        return result
    }

    private fun embedded(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { decodeBitmap(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Decodes album art at a reasonable size (about 400 px) so lists stay light. */
    fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 400) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Average color of the image (a 1x1 scaled copy). */
    fun averageColor(bitmap: Bitmap): Color {
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        return Color(pixel).copy(alpha = 1f)
    }

    /** A stable, pleasant color for covers that have no album art. */
    fun colorFor(name: String): Color {
        val hue = (name.hashCode().toLong() and 0x7fffffff) % 360
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.45f)))
    }
}
