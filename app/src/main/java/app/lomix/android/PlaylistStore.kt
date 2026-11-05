package app.lomix.android

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** One entry of a playlist: a library song or a tayyar gana (stems). */
data class PlaylistEntry(val kind: String, val ref: String, val title: String) {
    fun toQueueItem(): QueueItem? = when (kind) {
        KIND_SONG -> QueueItem.Song(Uri.parse(ref), title)
        KIND_STEM -> QueueItem.Stems(ref, title)
        else -> null
    }

    companion object {
        const val KIND_SONG = "song"
        const val KIND_STEM = "stem"

        fun of(item: QueueItem): PlaylistEntry = when (item) {
            is QueueItem.Song -> PlaylistEntry(KIND_SONG, item.uri.toString(), item.title)
            is QueueItem.Stems -> PlaylistEntry(KIND_STEM, item.setId, item.title)
        }

        fun ofSong(song: LibrarySong): PlaylistEntry =
            PlaylistEntry(KIND_SONG, song.uri, song.title)

        fun ofStem(set: StemSet): PlaylistEntry =
            PlaylistEntry(KIND_STEM, set.id, set.name)
    }
}

/** A named playlist (v1.6), Poweramp-style: an ordered list of entries. */
data class Playlist(val id: String, val name: String, val entries: List<PlaylistEntry>)

object PlaylistStore {

    private const val PREFS = "lomix_playlists"
    private const val KEY = "playlists_json"

    fun load(context: Context): List<Playlist> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val entriesArray = o.getJSONArray("entries")
                val entries = (0 until entriesArray.length()).map { j ->
                    val e = entriesArray.getJSONObject(j)
                    PlaylistEntry(e.getString("kind"), e.getString("ref"), e.getString("title"))
                }
                Playlist(o.getString("id"), o.getString("name"), entries)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, playlists: List<Playlist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val entriesArray = JSONArray()
            playlist.entries.forEach { e ->
                entriesArray.put(
                    JSONObject().put("kind", e.kind).put("ref", e.ref).put("title", e.title)
                )
            }
            array.put(
                JSONObject().put("id", playlist.id).put("name", playlist.name)
                    .put("entries", entriesArray)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun create(context: Context, name: String): List<Playlist> {
        val playlist = Playlist(
            id = "pl_" + System.currentTimeMillis().toString(36),
            name = name,
            entries = emptyList()
        )
        val list = load(context) + playlist
        save(context, list)
        return list
    }

    fun delete(context: Context, id: String): List<Playlist> {
        val list = load(context).filter { it.id != id }
        save(context, list)
        return list
    }

    fun setEntries(context: Context, id: String, entries: List<PlaylistEntry>): List<Playlist> {
        val list = load(context).map {
            if (it.id == id) it.copy(entries = entries) else it
        }
        save(context, list)
        return list
    }
}
