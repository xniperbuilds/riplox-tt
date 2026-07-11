package com.xniperbuilds.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ek failed download (retry ke liye poora data). */
data class FailedItem(
    val id: Long,
    val link: String,
    val title: String,
    val isAudio: Boolean,
    val error: String,
    val time: Long
)

/**
 * Failed downloads ka APNA store (failed.json) — WorkManager ke FAILED WorkInfo pe bharosa
 * nahi (wo prune ho jate + purane records me retry-data nahi hota tha). Ye reliable hai:
 * reboot/prune ke baad bhi Retry ka poora data yahin milta hai.
 */
object FailedStore {
    private const val FILE = "failed.json"

    private fun file(c: Context) = File(c.filesDir, FILE)

    private fun read(c: Context): MutableList<FailedItem> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                FailedItem(
                    o.getLong("id"), o.getString("link"), o.getString("title"),
                    o.optBoolean("isAudio", false), o.optString("error", ""), o.getLong("time")
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(c: Context, list: List<FailedItem>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("link", r.link); put("title", r.title)
                put("isAudio", r.isAudio); put("error", r.error); put("time", r.time)
            })
        }
        file(c).writeText(arr.toString())
    }

    @Synchronized
    fun add(c: Context, link: String, title: String, isAudio: Boolean, error: String) {
        val now = System.currentTimeMillis()
        val list = read(c)
        list.removeAll { it.link == link && it.isAudio == isAudio } // same link duplicate na ho
        // id STRICTLY unique (do fails ek hi ms me → same id → list key crash). Newest se +1.
        val id = maxOf(now, (list.firstOrNull()?.id ?: 0L) + 1)
        list.add(0, FailedItem(id, link, title, isAudio, error, now))
        while (list.size > 50) list.removeAt(list.size - 1)
        write(c, list)
    }

    fun all(c: Context): List<FailedItem> = read(c)

    @Synchronized
    fun remove(c: Context, id: Long) {
        val list = read(c); list.removeAll { it.id == id }; write(c, list)
    }

    @Synchronized
    fun clear(c: Context) {
        try { file(c).delete() } catch (_: Exception) {}
    }
}
