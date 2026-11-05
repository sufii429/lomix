package app.lomix.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.Color

/**
 * Album art (v1.6): the picture embedded inside the song file itself,
 * read with MediaMetadataRetriever and cached in memory. Songs without
 * embedded art get a deterministic two-colour gradient (picked by the
 * title's hash, so a song always wears the same colours).
 */
object Artwork {

    private val cache = LruCache<String, Bitmap>(24)

    fun load(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val bitmap = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            null
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    /** Fallback gradient pairs for songs with no embedded art. */
    val FALLBACKS: List<Pair<Color, Color>> = listOf(
        Color(0xFFFFB75E) to Color(0xFFB84A6E),
        Color(0xFF5E9CFF) to Color(0xFF3B4AB8),
        Color(0xFF7BD88F) to Color(0xFF2E7D5B),
        Color(0xFFC9A7FF) to Color(0xFF5B3BA8),
        Color(0xFFFF8A80) to Color(0xFFB83A3A),
        Color(0xFF6FE3D4) to Color(0xFF1F7A8C)
    )

    fun fallbackFor(title: String): Pair<Color, Color> {
        val h = title.hashCode()
        val idx = ((h % FALLBACKS.size) + FALLBACKS.size) % FALLBACKS.size
        return FALLBACKS[idx]
    }
}
