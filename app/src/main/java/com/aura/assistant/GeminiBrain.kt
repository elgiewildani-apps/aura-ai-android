package com.aura.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeminiBrain {

    private val conversationHistory = mutableListOf<Pair<String, String>>()

    suspend fun processInput(context: Context, userText: String): String {
        val clean = userText.trim()
        if (clean.isEmpty()) return ""

        val offlineResult = tryOfflineCommand(context, clean)
        if (offlineResult != null) {
            recordTurn(clean, offlineResult)
            return offlineResult
        }

        val apiKey = AuraConfig.getApiKey(context)
        if (apiKey.isNotEmpty()) {
            val aiResponse = callGeminiApi(context, apiKey, clean)
            recordTurn(clean, aiResponse)
            return aiResponse
        }

        val fallback = "Aku mengerti ucapanmu: "$clean". Untuk ngobrol bebas 24 jam tentang topik apa saja, masukkan Gemini API Key di layar utama AURA."
        recordTurn(clean, fallback)
        return fallback
    }

    private fun recordTurn(user: String, assistant: String) {
        synchronized(conversationHistory) {
            conversationHistory.add(user to assistant)
            while (conversationHistory.size > 10) {
                conversationHistory.removeAt(0)
            }
        }
    }

    private fun tryOfflineCommand(context: Context, text: String): String? {
        val lower = text.lowercase(Locale.getDefault())

        if (lower.contains("jam berapa") || lower.contains("pukul berapa")) {
            val now = SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date())
            return "Sekarang pukul $now."
        }
        if (lower.contains("tanggal berapa") || lower.contains("hari apa")) {
            val date = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date())
            return "Hari ini $date."
        }

        if (lower.contains("alarm")) {
            val match = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hourOnly = Regex("jam\\s*(\\d{1,2})").find(lower)
            val h = match?.groupValues?.get(1)?.toIntOrNull()
                ?: hourOnly?.groupValues?.get(1)?.toIntOrNull()
            val m = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
            if (h != null) {
                val params = JSONObject().put("hour", h).put("minute", m).put("label", "Alarm AURA")
                return DeviceController.executeAction(context, "SET_ALARM", params)
            }
        }

        if (lower.contains("timer")) {
            val minMatch = Regex("(\\d+)\\s*menit").find(lower)
            val secMatch = Regex("(\\d+)\\s*detik").find(lower)
            val totalSec = when {
                minMatch != null -> (minMatch.groupValues[1].toIntOrNull() ?: 1) * 60
                secMatch != null -> secMatch.groupValues[1].toIntOrNull() ?: 30
                else -> null
            }
            if (totalSec != null) {
                val params = JSONObject().put("seconds", totalSec).put("label", "Timer AURA")
                return DeviceController.executeAction(context, "SET_TIMER", params)
            }
        }

        if (lower.contains("senter") || lower.contains("flashlight")) {
            val off = lower.contains("matikan") || lower.contains("off") || lower.contains("tutup")
            val params = JSONObject().put("on", !off)
            return DeviceController.executeAction(context, "TORCH", params)
        }

        if (lower.contains("volume") || lower.contains("suara")) {
            val mode = when {
                lower.contains("naik") || lower.contains("besar") || lower.contains("kencang") -> "up"
                lower.contains("turun") || lower.contains("kecil") || lower.contains("pelan") -> "down"
                lower.contains("bisu") || lower.contains("mute") || lower.contains("diam") -> "mute"
                else -> "unmute"
            }
            return DeviceController.executeAction(context, "VOLUME", JSONObject().put("mode", mode))
        }

        if (lower.contains("notifikasi") || lower.contains("pesan masuk") || lower.contains("chat masuk")) {
            return DeviceController.executeAction(context, "READ_NOTIF", JSONObject())
        }

        if (lower.contains("kai") || lower.contains("tiket kereta")) {
            val dest = extractAfterKeyword(lower, listOf("ke ", "tujuan "))
            return DeviceController.executeAction(context, "KAI", JSONObject().put("destination", dest))
        }

        if (lower.contains("traveloka") || lower.contains("tiket pesawat") || lower.contains("booking hotel")) {
            val cat = when {
                lower.contains("hotel") -> "hotel"
                lower.contains("kereta") -> "kereta"
                lower.contains("bus") -> "bus"
                else -> "pesawat"
            }
            val dest = extractAfterKeyword(lower, listOf("ke ", "tujuan ", "di "))
            return DeviceController.executeAction(
                context,
                "TRAVELOKA",
                JSONObject().put("category", cat).put("destination", dest)
            )
        }

        if (lower.contains("gojek") || lower.contains("goride") || lower.contains("gocar") || lower.contains("gofood")) {
            val svc = when {
                lower.contains("gocar") || lower.contains("mobil") -> "gocar"
                lower.contains("gofood") || lower.contains("makan") -> "gofood"
                else -> "goride"
            }
            val dest = extractAfterKeyword(lower, listOf("ke ", "tujuan ", "pesan "))
            return DeviceController.executeAction(
                context,
                "GOJEK",
                JSONObject().put("service", svc).put("destination", dest)
            )
        }

        if (lower.contains("youtube") || lower.startsWith("putar ")) {
            val q = lower
                .replace("buka youtube", "")
                .replace("putar di youtube", "")
                .replace("cari di youtube", "")
                .replace("youtube", "")
                .replace("putar ", "")
                .trim()
            return DeviceController.executeAction(context, "YOUTUBE", JSONObject().put("query", q))
        }

        if (lower.contains("whatsapp") || lower.startsWith("wa ") || lower.contains("kirim wa")) {
            val afterKe = extractAfterKeyword(text, listOf("ke ", "kepada "))
            val parts = afterKe.split(" ", limit = 2)
            val contact = parts.getOrElse(0) { "" }
            val msg = parts.getOrElse(1) { "" }
            return DeviceController.executeAction(
                context,
                "WHATSAPP",
                JSONObject().put("contact", contact).put("message", msg)
            )
        }

        if (lower.startsWith("telepon ") || lower.startsWith("telpon ") || lower.startsWith("panggil ")) {
            val target = text.substringAfter(" ").trim()
            return DeviceController.executeAction(context, "CALL", JSONObject().put("target", target))
        }

        if (lower.contains("navigasi") || lower.contains("rute ke") || lower.contains("maps")) {
            val dest = extractAfterKeyword(lower, listOf("ke ", "menuju "))
            return DeviceController.executeAction(context, "MAPS", JSONObject().put("destination", dest))
        }

        if (lower == "kembali" || lower == "back") {
            return DeviceController.executeAction(context, "SCREEN_CMD", JSONObject().put("cmd", "back"))
        }
        if (lower == "ke beranda" || lower == "home") {
            return DeviceController.executeAction(context, "SCREEN_CMD", JSONObject().put("cmd", "home"))
        }

        return null
    }

    private fun extractAfterKeyword(text: String, keywords: List<String>): String {
        val lower = text.lowercase()
        for (kw in keywords) {
            val idx = lower.indexOf(kw)
            if (idx != -1) {
                return text.substring(idx + kw.length).trim()
            }
        }
        return ""
    }

    private suspend fun callGeminiApi(context: Context, apiKey: String, userText: String): String =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                }

                val systemInstruction =
                    "Kamu adalah AURA, asisten AI pribadi Android 24/7 yang ramah, cerdas, dan berbicara Bahasa Indonesia natural (singkat, jelas, enak didengar lewat suara TTS, hindari simbol bintang atau pagar).
" +
                    "Jika pengguna meminta melakukan tindakan di HP, jawab singkat lalu akhiri dengan tepat SATU tag JSON di baris paling akhir:
" +
                    "[ACTION:{"type":"NAMA_AKSI","params":{...}}]
" +
                    "Daftar NAMA_AKSI yang didukung:
" +
                    "- SET_ALARM: {"hour":5,"minute":30,"label":"Bangun"}
" +
                    "- SET_TIMER: {"seconds":300,"label":"Masak"}
" +
                    "- TORCH: {"on":true}
" +
                    "- VOLUME: {"mode":"up|down|mute|unmute"}
" +
                    "- CALL: {"target":"nama atau nomor"}
" +
                    "- WHATSAPP: {"contact":"nama atau nomor","message":"isi pesan"}
" +
                    "- KAI: {"origin":"asal","destination":"tujuan","date":"besok"}
" +
                    "- TRAVELOKA: {"category":"pesawat|hotel|kereta|bus","destination":"tujuan"}
" +
                    "- GOJEK: {"service":"goride|gocar|gofood","destination":"tujuan"}
" +
                    "- YOUTUBE: {"query":"judul video atau lagu"}
" +
                    "- MAPS: {"destination":"alamat tujuan"}
" +
                    "- READ_NOTIF: {}
" +
                    "- SCREEN_CMD: {"cmd":"back|home|scroll_down|scroll_up|click|type","target":"teks tombol atau input"}
" +
                    "- OPEN_APP: {\"app\":\"nama aplikasi\"}"
" +
                    "Jika hanya obrolan biasa tanpa perintah HP, jangan sertakan tag [ACTION:...]."

                val contentsArray = JSONArray()
                synchronized(conversationHistory) {
                    for ((u, a) in conversationHistory.takeLast(6)) {
                        contentsArray.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", u))))
                        contentsArray.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", a))))
                    }
                }
                contentsArray.put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))
                )

                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
                    .put("contents", contentsArray)

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val rawResponse = stream.bufferedReader().use { it.readText() }

                if (code !in 200..299) {
                    return@withContext "Koneksi AI bermasalah (kode $code). Periksa API Key atau koneksi internetmu."
                }

                val json = JSONObject(rawResponse)
                val replyText = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                val actionRegex = Regex("\\[ACTION:(\\{.*?\\})\\]", RegexOption.DOT_MATCHES_ALL)
                val match = actionRegex.find(replyText)
                if (match != null) {
                    val spokenPart = replyText.removeRange(match.range).trim()
                    val actionObj = JSONObject(match.groupValues[1])
                    val actionType = actionObj.optString("type", "")
                    val params = actionObj.optJSONObject("params") ?: JSONObject()
                    val execFeedback = withContext(Dispatchers.Main) {
                        DeviceController.executeAction(context, actionType, params)
                    }
                    return@withContext if (spokenPart.isNotEmpty()) "$spokenPart $execFeedback" else execFeedback
                }

                return@withContext replyText.replace("*", "").replace("#", "")
            } catch (e: Exception) {
                return@withContext "Maaf, jaringan sedang tidak stabil: ${e.localizedMessage ?: "error"}"
            }
        }
}
