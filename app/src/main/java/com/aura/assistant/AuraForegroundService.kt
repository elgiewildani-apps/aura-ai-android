package com.aura.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AuraForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "aura_247_channel"
        const val NOTIF_ID = 2407
        const val ACTION_STOP = "com.aura.assistant.STOP_SERVICE"
        const val ACTION_TOGGLE_LISTEN = "com.aura.assistant.TOGGLE_LISTEN"
        const val BROADCAST_LOG = "com.aura.assistant.CHAT_LOG"
        const val EXTRA_MESSAGE = "extra_message"

        @Volatile
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isPaused = false
    private var isSpeaking = false

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var orbBadge: TextView? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = buildNotification("AURA AI aktif 24/7 mendengarkan suaramu...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        acquireWakeLock()
        tts = TextToSpeech(this, this)
        setupFloatingOrb()
        initSpeechRecognizer()
        mainHandler.postDelayed({ startListeningLoop() }, 600)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AuraConfig.set247Active(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LISTEN -> {
                isPaused = !isPaused
                if (isPaused) {
                    stopListening()
                    updateOrbText("JEDA", "#F59E0B")
                    updateNotification("AURA dijeda. Ketuk orb untuk melanjutkan.")
                } else {
                    updateOrbText("DENGAR", "#10B981")
                    updateNotification("AURA aktif 24/7 mendengarkan...")
                    startListeningLoop()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopListening()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        removeFloatingOrb()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("id", "ID"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    mainHandler.post { updateOrbText("BICARA", "#38BDF8") }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({
                        if (!isPaused && isRunning) startListeningLoop()
                    }, 350)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({
                        if (!isPaused && isRunning) startListeningLoop()
                    }, 500)
                }
            })
            ttsReady = true
            speak("Aura aktif dan siap membantumu.")
        }
    }

    private fun speak(text: String) {
        sendLogBroadcast("AURA: $text")
        if (ttsReady && text.isNotBlank()) {
            stopListening()
            val params = Bundle()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aura_utt_${System.currentTimeMillis()}")
        } else {
            mainHandler.postDelayed({ if (!isPaused && isRunning) startListeningLoop() }, 400)
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendLogBroadcast("SISTEM: SpeechRecognizer tidak tersedia di perangkat ini.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateOrbText("DENGAR", "#10B981")
                }

                override fun onBeginningOfSpeech() {
                    updateOrbText("REKAM", "#38BDF8")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    updateOrbText("PROSES", "#A855F7")
                }

                override fun onError(error: Int) {
                    if (isPaused || isSpeaking || !isRunning) return
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 4000L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                        else -> 500L
                    }
                    mainHandler.postDelayed({ startListeningLoop() }, delay)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull()?.trim().orEmpty()
                    if (heard.isNotEmpty()) {
                        handleSpokenText(heard)
                    } else if (!isPaused && !isSpeaking && isRunning) {
                        mainHandler.postDelayed({ startListeningLoop() }, 400)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleSpokenText(heard: String) {
        val requireWake = AuraConfig.isWakeWordRequired(this)
        val lower = heard.lowercase(Locale.getDefault())
        val cleanedCommand = if (requireWake) {
            if (!lower.contains("aura")) {
                if (!isPaused && !isSpeaking && isRunning) {
                    mainHandler.postDelayed({ startListeningLoop() }, 350)
                }
                return
            }
            heard.replace(Regex("(?i)halo\\s+aura|hai\\s+aura|aura"), "").trim().ifEmpty { "Halo, ada yang bisa kubantu?" }
        } else {
            heard
        }

        sendLogBroadcast("KAMU: $cleanedCommand")
        serviceScope.launch {
            val reply = GeminiBrain.processInput(applicationContext, cleanedCommand)
            speak(reply)
        }
    }

    private fun startListeningLoop() {
        if (isPaused || isSpeaking || !isRunning) return
        try {
            if (speechRecognizer == null) {
                initSpeechRecognizer()
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            mainHandler.postDelayed({ startListeningLoop() }, 1200)
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun setupFloatingOrb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_floating_orb, null)
            orbBadge = floatingView?.findViewById(R.id.tvOrbBadge)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 260
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var downTime = 0L

            floatingView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        downTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = Math.abs(event.rawX - initialTouchX) + Math.abs(event.rawY - initialTouchY)
                        val elapsed = System.currentTimeMillis() - downTime
                        if (moved < 15f) {
                            if (elapsed > 600L) {
                                val openMain = Intent(this, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(openMain)
                            } else {
                                val toggleIntent = Intent(this, AuraForegroundService::class.java).apply {
                                    action = ACTION_TOGGLE_LISTEN
                                }
                                startService(toggleIntent)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(floatingView, params)
        } catch (_: Exception) {
        }
    }

    private fun removeFloatingOrb() {
        try {
            if (floatingView != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {
        }
    }

    private fun updateOrbText(label: String, hexColor: String) {
        orbBadge?.text = label
        try {
            orbBadge?.setTextColor(android.graphics.Color.parseColor(hexColor))
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraAssistant::Voice247WakeLock").apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AURA AI 24/7 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AuraForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura_launcher)
            .setContentTitle("AURA AI 24/7 Aktif")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(0, "Matikan", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(contentText))
    }

    private fun sendLogBroadcast(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, msg)
        }
        sendBroadcast(intent)
    }
}
