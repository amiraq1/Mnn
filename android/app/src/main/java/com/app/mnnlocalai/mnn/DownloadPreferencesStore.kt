package com.app.mnnlocalai.mnn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DownloadSettings(val completionNotificationsEnabled: Boolean, val cellularSpeedLimitKbps: Int)
data class DownloadHistoryEntry(val timestampMs: Long, val modelName: String, val engine: String, val bytes: Long, val durationMs: Long, val averageBytesPerSecond: Double, val outcome: String, val errorMessage: String?)

class DownloadPreferencesStore(context: Context) {
  private val preferences = context.getSharedPreferences("mnn-local-ai-downloads", Context.MODE_PRIVATE)

  fun settings(): DownloadSettings = DownloadSettings(
    completionNotificationsEnabled = preferences.getBoolean("completionNotificationsEnabled", true),
    cellularSpeedLimitKbps = preferences.getInt("cellularSpeedLimitKbps", 0),
  )

  fun updateSettings(completionNotificationsEnabled: Boolean, cellularSpeedLimitKbps: Int): DownloadSettings {
    preferences.edit()
      .putBoolean("completionNotificationsEnabled", completionNotificationsEnabled)
      .putInt("cellularSpeedLimitKbps", cellularSpeedLimitKbps.coerceIn(0, 10 * 1024))
      .apply()
    return settings()
  }

  fun history(): List<DownloadHistoryEntry> {
    val serialized = preferences.getString("history", "[]") ?: "[]"
    return try {
      val array = JSONArray(serialized)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.getJSONObject(index)
          add(DownloadHistoryEntry(
            timestampMs = item.optLong("timestampMs"),
            modelName = item.optString("modelName", "نموذج محلي"),
            engine = item.optString("engine", "mnn"),
            bytes = item.optLong("bytes"),
            durationMs = item.optLong("durationMs"),
            averageBytesPerSecond = item.optDouble("averageBytesPerSecond"),
            outcome = item.optString("outcome", "failed"),
            errorMessage = item.optString("errorMessage").takeIf { it.isNotBlank() },
          ))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun add(entry: DownloadHistoryEntry) {
    val entries = listOf(entry) + history()
    val array = JSONArray()
    entries.take(16).forEach { item ->
      array.put(JSONObject().apply {
        put("timestampMs", item.timestampMs)
        put("modelName", item.modelName)
        put("engine", item.engine)
        put("bytes", item.bytes)
        put("durationMs", item.durationMs)
        put("averageBytesPerSecond", item.averageBytesPerSecond)
        put("outcome", item.outcome)
        put("errorMessage", item.errorMessage ?: "")
      })
    }
    preferences.edit().putString("history", array.toString()).apply()
  }

  fun clearHistory() = preferences.edit().remove("history").apply()
}
