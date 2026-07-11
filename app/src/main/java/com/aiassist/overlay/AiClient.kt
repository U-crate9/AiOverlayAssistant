package com.aiassist.overlay

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Sends a screenshot + a text instruction to whichever AI provider the user
 * configured, and returns the plain-text answer. Supports:
 *  - Google Gemini (generativelanguage.googleapis.com) — has a free tier
 *  - Any OpenAI-compatible vision endpoint (OpenAI, or compatible gateways)
 */
class AiClient(private val prefs: PrefsManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val CONCISE_PREFIX =
        "Songkhipto o sohoj bhasay uttor dao — joto tuku dorkar totukui bolo, extra kotha ba bhumika likho na.\n\n"

    /** Runs on a background thread. Returns the AI's reply text, or an error message. */
    fun askAboutScreen(bitmap: Bitmap, instruction: String, history: List<Pair<String, String>> = emptyList()): String {
        if (prefs.apiKey.isBlank()) {
            return "API key set kora hoyni. App e giye Settings theke API key din."
        }
        val base64Image = bitmapToBase64(bitmap)
        return try {
            when (prefs.provider) {
                "gemini" -> askGemini(base64Image, instruction, history)
                else -> askOpenAiCompatible(base64Image, instruction, history)
            }
        } catch (e: Exception) {
            "Somossha hoyeche: ${e.message ?: "unknown error"}"
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        // Resize down for speed/cost — full resolution isn't needed for the model to read a screen.
        val scale = 1024.0 / maxOf(bitmap.width, bitmap.height)
        val resized = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap
        resized.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun askGemini(base64Image: String, instruction: String, history: List<Pair<String, String>>): String {
        val model = prefs.model.ifBlank { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${prefs.apiKey}"

        val contents = JSONArray()
        history.forEach { (role, text) ->
            val geminiRole = if (role == "user") "user" else "model"
            contents.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray()
                .put(JSONObject().put("text", CONCISE_PREFIX + instruction))
                .put(JSONObject().put(
                    "inline_data",
                    JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }
                ))
            )
        })

        val body = JSONObject().apply { put("contents", contents) }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: return "Empty response"
            if (!response.isSuccessful) return "API error (${response.code}): $text"
            val json = JSONObject(text)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun askOpenAiCompatible(base64Image: String, instruction: String, history: List<Pair<String, String>>): String {
        val url = prefs.baseUrl.ifBlank { "https://api.openai.com/v1/chat/completions" }
        val model = prefs.model.ifBlank { "gpt-4o-mini" }

        val messages = JSONArray()
        history.forEach { (role, text) ->
            val chatRole = if (role == "user") "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", chatRole)
                put("content", text)
            })
        }

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", CONCISE_PREFIX + instruction))
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${prefs.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: return "Empty response"
            if (!response.isSuccessful) return "API error (${response.code}): $text"
            val json = JSONObject(text)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }
}
