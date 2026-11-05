package app.lomix.android

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * One kind of stem in a separated song. [fileToken] is the word a
 * separation tool puts at the end of the file name (MVSEP exports look
 * like "mann-ki-lagan_..._0_vocals.mp3"), which is how an imported file
 * is recognised without the user having to sort anything by hand.
 */
enum class StemKind(val label: String, val fileToken: String) {
    VOCALS("Awaz", "vocals"),
    DRUMS("Drum", "drums"),
    BASS("Bass", "bass"),
    GUITAR("Guitar", "guitar"),
    PIANO("Piano", "piano"),
    OTHER("Baaki", "other"),
    INSTRUM("Music", "instrum")
}

/** An imported separated song: a display name + stem kind -> file name. */
data class StemSet(
    val id: String,
    val name: String,
    val stems: Map<StemKind, String>
)

/**
 * Stores "tayyar ganay" — songs Mazeen separated himself with an outside
 * tool (MVSEP) and imported into Lomix as a zip (v1.4).
 *
 * Layout: filesDir/stemsets/index.json lists the sets; each set's audio
 * files live in filesDir/stemsets/<id>/. Import classifies each zip
 * entry by the token at the end of its name; the "instrum" stem (all the
 * music in one file) is dropped whenever the detailed music stems
 * (bass/drums/guitar/piano/other) are present, because playing both
 * would double the music — it is kept only for 2-stem exports, where
 * it plays as the "Music" part next to the vocals.
 */
object StemStore {

    private fun rootDir(context: Context): File =
        File(context.filesDir, "stemsets").apply { mkdirs() }

    fun setDir(context: Context, set: StemSet): File =
        File(rootDir(context), set.id)

    fun stemFile(context: Context, set: StemSet, fileName: String): File =
        File(setDir(context, set), fileName)

    private fun indexFile(context: Context): File = File(rootDir(context), "index.json")

    fun loadSets(context: Context): List<StemSet> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val out = mutableListOf<StemSet>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val stemsObj = obj.getJSONObject("stems")
                val stems = mutableMapOf<StemKind, String>()
                val keys = stemsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val kind = StemKind.values().firstOrNull { it.name == key } ?: continue
                    stems[kind] = stemsObj.getString(key)
                }
                if (stems.isNotEmpty()) {
                    out.add(StemSet(obj.getString("id"), obj.getString("name"), stems))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSets(context: Context, sets: List<StemSet>) {
        val array = JSONArray()
        for (set in sets) {
            val stemsObj = JSONObject()
            set.stems.forEach { (kind, fileName) -> stemsObj.put(kind.name, fileName) }
            array.put(
                JSONObject()
                    .put("id", set.id)
                    .put("name", set.name)
                    .put("stems", stemsObj)
            )
        }
        indexFile(context).writeText(array.toString())
    }

    /** Which stem kind a file name belongs to, from its last _token. */
    private fun classify(fileName: String): StemKind? {
        val base = fileName.substringAfterLast('/').lowercase()
        val noExt = base.substringBeforeLast('.', base)
        val token = noExt.substringAfterLast('_', noExt)
        return when (token) {
            "vocals", "vocal" -> StemKind.VOCALS
            "drums", "drum" -> StemKind.DRUMS
            "bass" -> StemKind.BASS
            "guitar" -> StemKind.GUITAR
            "piano" -> StemKind.PIANO
            "other" -> StemKind.OTHER
            "instrum", "instrumental" -> StemKind.INSTRUM
            else -> null
        }
    }

    /**
     * A display name from a stem file name: MVSEP names look like
     * "mann-ki-lagan_bs_roformer_sw_0_vocals.mp3" — keep the words
     * before the model gibberish starts, so this becomes "Mann Ki Lagan".
     */
    private fun deriveName(anyStemFileName: String, fallback: String): String {
        val base = anyStemFileName.substringAfterLast('/').substringBeforeLast('.')
        val words = base.split('_', '-').filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        for (word in words) {
            val w = word.lowercase()
            if (w in MODEL_TOKENS || w.contains("roformer") || w.contains("demucs") ||
                w.contains("mdx") || w.all { it.isDigit() }
            ) break
            kept.add(word)
        }
        if (kept.isEmpty()) return fallback
        return kept.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * Import a separation zip. Returns the new set, or null when the zip
     * holds fewer than two recognisable stems. Runs on the caller's
     * thread — call it from a background thread (zips are tens of MB).
     */
    fun importZip(context: Context, uri: Uri, zipDisplayName: String): StemSet? {
        val id = System.currentTimeMillis().toString()
        val dir = File(rootDir(context), id)
        dir.mkdirs()
        val found = mutableMapOf<StemKind, String>()
        var firstStemName: String? = null
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            ZipInputStream(stream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val kind = classify(entry.name)
                        if (kind != null && !found.containsKey(kind)) {
                            val safeName = entry.name.substringAfterLast('/')
                            val outFile = File(dir, safeName)
                            outFile.outputStream().buffered().use { zip.copyTo(it) }
                            found[kind] = safeName
                            if (firstStemName == null) firstStemName = safeName
                        } else {
                            // Drain unrecognised entries so the stream
                            // stays aligned for the next entry.
                            val scratch = ByteArray(64 * 1024)
                            while (zip.read(scratch) >= 0) {
                                // discard
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            return null
        }
        // The combined "instrum" stem doubles the music when the
        // detailed stems exist — drop it in that case.
        val hasDetail = found.keys.any {
            it == StemKind.BASS || it == StemKind.DRUMS || it == StemKind.GUITAR ||
                it == StemKind.PIANO || it == StemKind.OTHER
        }
        if (hasDetail) found.remove(StemKind.INSTRUM)
        if (found.size < 2) {
            dir.deleteRecursively()
            return null
        }
        val fallback = zipDisplayName
            .removePrefix("mvsep_")
            .substringBeforeLast('.')
            .trim()
            .ifEmpty { "Tayyar Gana" }
        val set = StemSet(id, deriveName(firstStemName ?: "", fallback), found.toMap())
        saveSets(context, loadSets(context) + set)
        return set
    }

    fun delete(context: Context, set: StemSet) {
        setDir(context, set).deleteRecursively()
        saveSets(context, loadSets(context).filter { it.id != set.id })
    }

    private val MODEL_TOKENS = setOf(
        "bs", "sw", "mt", "demucs", "htdemucs", "roformer", "mdx", "v4", "v2",
        "melband", "stem", "stems", "sep", "separation"
    )
}
