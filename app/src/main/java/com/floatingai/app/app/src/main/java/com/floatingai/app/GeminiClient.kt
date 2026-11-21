package com.floatingai.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(msg: String) : Exception(msg)

object GeminiClient {
    class Msg(val role: String, val text: String)

    @Volatile private var conn: HttpURLConnection? = null
    fun cancel() { try { conn?.disconnect() } catch (_: Throwable) { } }

    private const val SYSTEM = "Kamu asisten belajar yang cepat dan ringkas. Jawab dalam bahasa yang dipakai pengguna. " +
        "Jelaskan langkah dan alasan singkat agar pengguna paham, bukan hanya hasil akhir. Hindari format markdown berat."

    fun stream(key: String, model: String, history: List<Msg>, prompt: String, image: ByteArray?): Flow<String> = flow {
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", it.role).put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val gen = JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1500)
        if (model.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("generationConfig", gen)

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                throw GeminiException(when (code) {
                    400, 401, 403 -> "API key tidak valid atau tidak punya akses (HTTP $code). Periksa API key & nama model."
                    404 -> "Model tidak ditemukan. Ganti nama model di aplikasi utama."
                    429 -> "Kuota/batas permintaan tercapai. Coba lagi sebentar."
                    else -> "Server error (HTTP $code)."
                })
            }
            var got = false
            val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 8192)
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.substring(5).trim()
                if (js.isEmpty() || js == "[DONE]") continue
                val ps = JSONObject(js).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until ps.length()) {
                    val t = ps.getJSONObject(i).optString("text")
                    if (t.isNotEmpty()) { got = true; emit(t) }
                }
            }
            if (!got) emit("(Tidak ada jawaban. Konten mungkin diblokir oleh filter keamanan.)")
        } finally {
            try { c.disconnect() } catch (_: Throwable) { }
            if (conn === c) conn = null
        }
    }.flowOn(Dispatchers.IO)
}
