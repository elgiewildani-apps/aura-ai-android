package com.aura.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.URLEncoder

object DeviceController {

    private var isTorchOn = false

    fun executeAction(context: Context, type: String, params: JSONObject): String {
        return when (type.uppercase()) {
            "SET_ALARM" -> setSystemAlarm(
                context,
                params.optInt("hour", 6),
                params.optInt("minute", 0),
                params.optString("label", "Alarm AURA")
            )
            "SET_TIMER" -> setSystemTimer(
                context,
                params.optInt("seconds", 60),
                params.optString("label", "Timer AURA")
            )
            "TORCH" -> toggleFlashlight(context, params.optBoolean("on", !isTorchOn))
            "VOLUME" -> adjustVolume(context, params.optString("mode", "up"))
            "CALL" -> callContactOrNumber(context, params.optString("target", ""))
            "WHATSAPP" -> openWhatsApp(
                context,
                params.optString("contact", ""),
                params.optString("message", "")
            )
            "KAI" -> openKaiAccess(
                context,
                params.optString("origin", ""),
                params.optString("destination", ""),
                params.optString("date", "")
            )
            "TRAVELOKA" -> openTraveloka(
                context,
                params.optString("category", "pesawat"),
                params.optString("destination", "")
            )
            "GOJEK" -> openGojek(
                context,
                params.optString("service", "goride"),
                params.optString("destination", "")
            )
            "YOUTUBE" -> openYouTube(context, params.optString("query", ""))
            "MAPS" -> openMaps(context, params.optString("destination", ""))
            "READ_NOTIF" -> AuraNotificationListener.getRecentSummary()
            "SCREEN_CMD" -> {
                val ok = AuraAccessibilityService.instance?.performScreenCommand(
                    params.optString("cmd", ""),
                    params.optString("target", "")
                ) ?: false
                if (ok) "Perintah layar dijalankan." else "Pastikan Izin Kontrol Layar (Aksesibilitas) sudah aktif."
            }
            "OPEN_APP" -> launchByName(context, params.optString("app", ""))
            else -> "Perintah selesai diproses."
        }
    }

    private fun setSystemAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            String.format("Alarm sistem dipasang jam %02d:%02d untuk %s.", h, m, label)
        } catch (e: Exception) {
            "Gagal memasang alarm: ${e.message}"
        }
    }

    private fun setSystemTimer(context: Context, seconds: Int, label: String): String {
        val sec = seconds.coerceAtLeast(5)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, sec)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Timer selama $sec detik dimulai."
        } catch (e: Exception) {
            "Gagal memulai timer: ${e.message}"
        }
    }

    private fun toggleFlashlight(context: Context, turnOn: Boolean): String {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull() ?: return "Kamera tidak ditemukan."
            camManager.setTorchMode(cameraId, turnOn)
            isTorchOn = turnOn
            if (turnOn) "Senter HP dinyalakan." else "Senter HP dimatikan."
        } catch (e: Exception) {
            "Gagal mengatur senter: ${e.message}"
        }
    }

    private fun adjustVolume(context: Context, mode: String): String {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode.lowercase()) {
                "up", "naik" -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume dinaikkan."
                }
                "down", "turun" -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume diturunkan."
                }
                "mute", "diam" -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Suara dibisukan."
                }
                else -> {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "Suara diaktifkan kembali."
                }
            }
        } catch (e: Exception) {
            "Gagal mengubah volume."
        }
    }

    private fun callContactOrNumber(context: Context, target: String): String {
        val cleaned = target.trim()
        if (cleaned.isBlank()) return "Sebutkan nama kontak atau nomor tujuan."

        val phone = if (cleaned.any { it.isDigit() } && cleaned.count { it.isDigit() } >= 6) {
            cleaned.filter { it.isDigit() || it == '+' }
        } else {
            findPhoneNumberByName(context, cleaned) ?: return "Kontak $cleaned tidak ditemukan di buku telepon."
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Menghubungi $cleaned."
    }

    private fun openWhatsApp(context: Context, contactOrPhone: String, message: String): String {
        val phone = if (contactOrPhone.count { it.isDigit() } >= 8) {
            normalizeIndonesianPhone(contactOrPhone)
        } else {
            findPhoneNumberByName(context, contactOrPhone)?.let { normalizeIndonesianPhone(it) }
        }

        if (!phone.isNullOrBlank()) {
            if (message.isNotBlank()) {
                AuraAccessibilityService.queueTask(
                    AuraAccessibilityService.AutomationTask(
                        targetApp = "WHATSAPP",
                        recipientOrOrigin = phone,
                        destinationOrText = message
                    )
                )
            }
            val encoded = URLEncoder.encode(message, "UTF-8")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return if (message.isNotBlank()) "Membuka WhatsApp ke $contactOrPhone dan menyiapkan pesan." else "Membuka chat WhatsApp dengan $contactOrPhone."
        }

        val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: return "Aplikasi WhatsApp belum terpasang di HP ini."

        if (contactOrPhone.isNotBlank()) {
            AuraAccessibilityService.queueTask(
                AuraAccessibilityService.AutomationTask(
                    targetApp = "WHATSAPP",
                    recipientOrOrigin = contactOrPhone,
                    destinationOrText = message
                )
            )
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Membuka WhatsApp untuk $contactOrPhone."
    }

    private fun openKaiAccess(context: Context, origin: String, destination: String, date: String): String {
        val pkg = "com.kai.kaiticketing"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "Aplikasi Access by KAI belum terpasang di HP ini."
        AuraAccessibilityService.queueTask(
            AuraAccessibilityService.AutomationTask(
                targetApp = "KAI",
                recipientOrOrigin = origin,
                destinationOrText = destination,
                extraInfo = date
            )
        )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Membuka KAI Access untuk mencari tiket kereta tujuan $destination. Aku akan berhenti sebelum pembayaran."
    }

    private fun openTraveloka(context: Context, category: String, destination: String): String {
        val pkg = "com.traveloka.android"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "Aplikasi Traveloka belum terpasang di HP ini."
        AuraAccessibilityService.queueTask(
            AuraAccessibilityService.AutomationTask(
                targetApp = "TRAVELOKA",
                destinationOrText = destination,
                extraInfo = category
            )
        )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Membuka Traveloka kategori $category tujuan $destination."
    }

    private fun openGojek(context: Context, service: String, destination: String): String {
        val pkg = "com.gojek.app"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "Aplikasi Gojek belum terpasang di HP ini."
        AuraAccessibilityService.queueTask(
            AuraAccessibilityService.AutomationTask(
                targetApp = "GOJEK",
                destinationOrText = destination,
                extraInfo = service
            )
        )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Membuka Gojek layanan $service menuju $destination."
    }

    private fun openYouTube(context: Context, query: String): String {
        val q = query.trim()
        if (q.isNotBlank()) {
            AuraAccessibilityService.queueTask(
                AuraAccessibilityService.AutomationTask(
                    targetApp = "YOUTUBE",
                    destinationOrText = q
                )
            )
        }
        val ytLaunch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        return if (ytLaunch != null && q.isNotBlank()) {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", q)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(searchIntent)
            "Mencari dan memutar $q di YouTube."
        } else {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            "Membuka YouTube."
        }
    }

    private fun openMaps(context: Context, destination: String): String {
        val encoded = URLEncoder.encode(destination, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Memulai navigasi Google Maps ke $destination."
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            "Membuka peta menuju $destination."
        }
    }

    private fun launchByName(context: Context, appName: String): String {
        val lower = appName.lowercase()
        val pkg = when {
            lower.contains("whatsapp") || lower == "wa" -> "com.whatsapp"
            lower.contains("kai") || lower.contains("kereta") -> "com.kai.kaiticketing"
            lower.contains("traveloka") -> "com.traveloka.android"
            lower.contains("gojek") -> "com.gojek.app"
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("maps") || lower.contains("peta") -> "com.google.android.apps.maps"
            else -> ""
        }
        if (pkg.isNotEmpty()) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Membuka $appName."
            }
        }
        return "Aplikasi $appName tidak ditemukan di perangkat."
    }

    private fun findPhoneNumberByName(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(1)
            }
        }
        return null
    }

    private fun normalizeIndonesianPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("0") -> "62" + digits.substring(1)
            digits.startsWith("62") -> digits
            else -> digits
        }
    }
}
