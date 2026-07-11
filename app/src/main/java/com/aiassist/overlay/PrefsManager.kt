package com.aiassist.overlay

import android.content.Context
import org.json.JSONArray

/**
 * Stores API provider settings and saved quick-commands so the user
 * never has to retype instructions. Everything persists across app restarts.
 */
class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences("ai_overlay_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROVIDER = "provider"          // "gemini" or "openai_compatible"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"          // only used for openai_compatible
        private const val KEY_MODEL = "model"
        private const val KEY_COMMANDS = "saved_commands"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_AUTO_INTERVAL = "auto_interval_seconds"
        private const val KEY_AUTO_MODE_ON = "auto_mode_on"
        private const val KEY_HISTORY = "conversation_history"
        private const val MAX_HISTORY_TURNS = 12 // ~6 back-and-forth exchanges
    }

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "https://api.openai.com/v1/chat/completions") ?: ""
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var lastCommand: String
        get() = prefs.getString(KEY_LAST_COMMAND, "Amake ei screen ta dekhe bujhiye dao ki korte hobe.") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_COMMAND, value).apply()

    var autoIntervalSeconds: Int
        get() = prefs.getInt(KEY_AUTO_INTERVAL, 5)
        set(value) = prefs.edit().putInt(KEY_AUTO_INTERVAL, value).apply()

    var autoModeOn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MODE_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MODE_ON, value).apply()

    /** Conversation memory: list of (role, text) pairs, "user" or "model". Persists across restarts. */
    fun getHistory(): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            obj.getString("role") to obj.getString("text")
        }
    }

    fun addHistoryTurn(role: String, text: String) {
        val current = getHistory().toMutableList()
        current.add(role to text)
        val trimmed = if (current.size > MAX_HISTORY_TURNS) current.takeLast(MAX_HISTORY_TURNS) else current
        val arr = JSONArray()
        trimmed.forEach { (r, t) ->
            arr.put(org.json.JSONObject().apply { put("role", r); put("text", t) })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun getSavedCommands(): List<String> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return defaultCommands()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addCommand(command: String) {
        val current = getSavedCommands().toMutableList()
        if (command.isNotBlank() && !current.contains(command)) {
            current.add(0, command)
        }
        saveCommands(current)
    }

    fun removeCommand(command: String) {
        val current = getSavedCommands().toMutableList()
        current.remove(command)
        saveCommands(current)
    }

    private fun saveCommands(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_COMMANDS, arr.toString()).apply()
    }

    private fun defaultCommands(): List<String> = listOf(
        "Ei screen e ki hocche bujhiye bolo, ami atke gechi.",
        "পরবর্তী কী পদক্ষেপ নিতে হবে সংক্ষেপে বলো।",
        "Ei error ba somossha ta solve korar upay bolo."
    )
}
