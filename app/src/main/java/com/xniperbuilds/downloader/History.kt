package com.xniperbuilds.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ek download ka record. location = saved file ka URI/path. */
data class DownloadRecord(
    val id: Long,
    val title: String,
    val platform: String,
    val url: String,
    val location: String,
    val isAudio: Boolean,
    val time: Long
)

/** Download history — simple JSON file (app internal storage). TT app me trash nahi — delete seedha. */
object History {
    private const val HIST = "history.json"

    private fun file(c: Context) = File(c.filesDir, HIST)

    private fun read(c: Context): MutableList<DownloadRecord> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                DownloadRecord(
                    o.getLong("id"),
                    o.getString("title"),
                    o.optString("platform", "TT"),
                    o.getString("url"),
                    o.getString("location"),
                    o.optBoolean("isAudio", false),
                    o.getLong("time")
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(c: Context, list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("platform", r.platform)
                put("url", r.url)
                put("location", r.location)
                put("isAudio", r.isAudio)
                put("time", r.time)
            })
        }
        file(c).writeText(arr.toString())
    }

    @Synchronized
    fun add(c: Context, url: String, title: String, platform: String, location: String, isAudio: Boolean) {
        val now = System.currentTimeMillis()
        val list = read(c)
        // Keep the id STRICTLY unique: three parallel downloads can finish in the same
        // millisecond, and a shared id crashes the LazyColumn key and makes one delete remove
        // both. Hence: newest id + 1.
        val id = maxOf(now, (list.firstOrNull()?.id ?: 0L) + 1)
        list.add(0, DownloadRecord(id, title, platform, url, location, isAudio, now))
        write(c, list)
    }

    fun all(c: Context): List<DownloadRecord> = read(c)

    /**
     * Every record and its file. One write instead of N, and the records go FIRST — if a file
     * delete throws half way through, the user does not end up with a list of entries that all
     * point at files that may or may not still exist.
     * Returns how many files were actually removed (records are always cleared).
     */
    @Synchronized
    fun deleteAll(c: Context): Int {
        val list = read(c)
        write(c, emptyList())
        var removed = 0
        list.forEach { item ->
            try {
                if (item.location.startsWith("content://")) {
                    c.contentResolver.delete(android.net.Uri.parse(item.location), null, null)
                } else {
                    File(item.location).delete()
                }
                removed++
            } catch (_: Exception) {
            }
        }
        return removed
    }

    /** Record + asli file dono delete (best-effort — file pehle se gayi ho to bhi record hat jata). */
    @Synchronized
    fun delete(c: Context, id: Long) {
        val list = read(c)
        val item = list.find { it.id == id } ?: return
        list.removeAll { it.id == id }
        write(c, list)
        try {
            if (item.location.startsWith("content://")) {
                c.contentResolver.delete(android.net.Uri.parse(item.location), null, null)
            } else {
                File(item.location).delete()
            }
        } catch (_: Exception) {
        }
    }
}
