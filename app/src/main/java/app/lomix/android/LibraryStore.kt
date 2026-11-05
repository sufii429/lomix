package app.lomix.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One remembered song in the library (v1.6). */
data class LibrarySong(val uri: String, val title: String)

/**
 * The library: every song Mazeen has ever picked in Lomix, remembered
 * (uri + display name) in SharedPreferences so the Playlists tab can
 * list, replay and organise them. The file itself is never copied —
 * the persisted document-picker permission keeps the uri readable.
 */
object LibraryStore {

    private const val PREFS = "lomix_library"
    private const val KEY = "songs_json"

    fun load(context: Context): List<LibrarySong> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LibrarySong(o.getString("uri"), o.getString("title"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, songs: List<LibrarySong>) {
        val array = JSONArray()
        songs.forEach {
            array.put(JSONObject().put("uri", it.uri).put("title", it.title))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    /** Add a song (deduped by uri); returns the new list. */
    fun add(context: Context, song: LibrarySong): List<LibrarySong> {
        val list = load(context)
        if (list.any { it.uri == song.uri }) return list
        val newList = list + song
        save(context, newList)
        return newList
    }

    fun remove(context: Context, uri: String): List<LibrarySong> {
        val list = load(context).filter { it.uri != uri }
        save(context, list)
        return list
    }
}
