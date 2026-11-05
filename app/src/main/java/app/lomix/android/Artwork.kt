package app.lomix.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import java.io.File
import java.security.MessageDigest

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
        val custom = coverFile(context, uri)
        if (custom.exists()) {
            val bmp = BitmapFactory.decodeFile(custom.absolutePath)
            if (bmp != null) {
                cache.put(key, bmp)
                return bmp
            }
        }
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

    /** Bumped whenever a custom cover changes, so the UI reloads art. */
    val revision = mutableStateOf(0)

    private fun coverFile(context: Context, uri: Uri): File {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.jpg")
    }

    /** True when the user has set their own cover for this song. */
    fun hasCustomCover(context: Context, uri: Uri): Boolean =
        coverFile(context, uri).exists()

    /**
     * Save the user's own picture as this song's cover (v1.7) — for
     * mixes whose file carries no art of its own. Stored inside the
     * app, keyed by the song's uri; shown everywhere the embedded
     * picture would be.
     */
    fun setCustomCover(context: Context, uri: Uri, imageUri: Uri): Boolean {
        return try {
            val raw = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            val longest = maxOf(raw.width, raw.height)
            val bmp = if (longest > 1000) {
                val s = 1000f / longest
                Bitmap.createScaledBitmap(
                    raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true
                )
            } else raw
            coverFile(context, uri).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            cache.evictAll()
            revision.value++
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Remove the user's custom cover; the embedded art (if any) returns. */
    fun removeCustomCover(context: Context, uri: Uri) {
        coverFile(context, uri).delete()
        cache.evictAll()
        revision.value++
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
