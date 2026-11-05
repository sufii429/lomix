package app.lomix.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named, saved mix (v1.5): everything that makes up "ABHI KA MIX" —
 * speed, the Lofi Rang toggles, rain + volume, every ambient layer's
 * switch + volume, and per-stem volumes — so a favourite combination
 * comes back with one tap. Stored as JSON in SharedPreferences.
 */
data class MixPreset(
    val name: String,
    val speed: Float,
    val garam: Boolean,
    val naram: Boolean,
    val geela: Boolean,
    val vinyl: Boolean,
    val cassette: Boolean,
    val rainChoice: String,
    val rainVolume: Int,
    val ambient: Map<String, Pair<Boolean, Int>>,
    val stemVolumes: Map<String, Int>
)

object MixPresetStore {

    private const val PREFS = "lomix_presets"
    private const val KEY = "presets_json"

    fun load(context: Context): List<MixPreset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<MixPreset>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val ambObj = o.getJSONObject("ambient")
                val ambient = mutableMapOf<String, Pair<Boolean, Int>>()
                val aKeys = ambObj.keys()
                while (aKeys.hasNext()) {
                    val k = aKeys.next()
                    val v = ambObj.getJSONObject(k)
                    ambient[k] = v.getBoolean("on") to v.getInt("vol")
                }
                val stemObj = o.getJSONObject("stemVolumes")
                val stems = mutableMapOf<String, Int>()
                val sKeys = stemObj.keys()
                while (sKeys.hasNext()) {
                    val k = sKeys.next()
                    stems[k] = stemObj.getInt(k)
                }
                out.add(
                    MixPreset(
                        name = o.getString("name"),
                        speed = o.getDouble("speed").toFloat(),
                        garam = o.getBoolean("garam"),
                        naram = o.getBoolean("naram"),
                        geela = o.getBoolean("geela"),
                        vinyl = o.getBoolean("vinyl"),
                        cassette = o.getBoolean("cassette"),
                        rainChoice = o.getString("rainChoice"),
                        rainVolume = o.getInt("rainVolume"),
                        ambient = ambient,
                        stemVolumes = stems
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, presets: List<MixPreset>) {
        val array = JSONArray()
        for (p in presets) {
            val ambObj = JSONObject()
            p.ambient.forEach { (k, v) ->
                ambObj.put(k, JSONObject().put("on", v.first).put("vol", v.second))
            }
            val stemObj = JSONObject()
            p.stemVolumes.forEach { (k, v) -> stemObj.put(k, v) }
            array.put(
                JSONObject()
                    .put("name", p.name)
                    .put("speed", p.speed.toDouble())
                    .put("garam", p.garam)
                    .put("naram", p.naram)
                    .put("geela", p.geela)
                    .put("vinyl", p.vinyl)
                    .put("cassette", p.cassette)
                    .put("rainChoice", p.rainChoice)
                    .put("rainVolume", p.rainVolume)
                    .put("ambient", ambObj)
                    .put("stemVolumes", stemObj)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    /** Add or replace (same name) a preset; returns the new list. */
    fun add(context: Context, preset: MixPreset): List<MixPreset> {
        val list = load(context).filter { it.name != preset.name } + preset
        save(context, list)
        return list
    }

    fun delete(context: Context, name: String): List<MixPreset> {
        val list = load(context).filter { it.name != name }
        save(context, list)
        return list
    }
}
