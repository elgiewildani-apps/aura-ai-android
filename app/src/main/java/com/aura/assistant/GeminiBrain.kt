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

        val fallback = "Aku mengerti ucapanmu: \"$clean\". Untuk ngobrol bebas 24 jam tentang topik apa saja, masukkan Gemini API Key di layar utama AURA."
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
