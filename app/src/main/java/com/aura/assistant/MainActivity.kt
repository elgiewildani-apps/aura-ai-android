package com.aura.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnToggle247: Button
    private lateinit var tvPermissionSummary: TextView
    private lateinit var etApiKey: EditText
    private lateinit var cbWakeWord: CheckBox
    private lateinit var tvChatLog: TextView
    private lateinit var etManualCommand: EditText

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AuraForegroundService.EXTRA_MESSAGE) ?: return
            appendLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnToggle247 = findViewById(R.id.btnToggle247)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        etApiKey = findViewById(R.id.etApiKey)
        cbWakeWord = findViewById(R.id.cbWakeWord)
        tvChatLog = findViewById(R.id.tvChatLog)
        etManualCommand = findViewById(R.id.etManualCommand)

        tvChatLog.movementMethod = ScrollingMovementMethod()
        etApiKey.setText(AuraConfig.getApiKey(this))
        cbWakeWord.isChecked = AuraConfig.isWakeWordRequired(this)

        btnToggle247.setOnClickListener {
            if (AuraForegroundService.isRunning) {
                AuraConfig.set247Active(this, false)
                stopService(Intent(this, AuraForegroundService::class.java))
                refreshStatusUI()
            } else {
                if (!hasMicPermission()) {
                    requestAllBasicPermissions()
                    Toast.makeText(this, "Izinkan mikrofon dulu lalu tekan NYALAKAN 24/7 lagi.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                AuraConfig.set247Active(this, true)
                ContextCompat.startForegroundService(this, Intent(this, AuraForegroundService::class.java))
                btnToggle247.postDelayed({ refreshStatusUI() }, 350)
            }
        }

        findViewById<Button>(R.id.btnGrantBasic).setOnClickListener {
            requestAllBasicPermissions()
        }

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantBattery).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            AuraConfig.setApiKey(this, etApiKey.text.toString())
            Toast.makeText(this, "Gemini API Key disimpan!", Toast.LENGTH_SHORT).show()
        }

        cbWakeWord.setOnCheckedChangeListener { _, isChecked ->
            AuraConfig.setWakeWordRequired(this, isChecked)
        }

        findViewById<Button>(R.id.btnSendCommand).setOnClickListener {
            val cmd = etManualCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            etManualCommand.setText("")
            appendLog("KAMU: $cmd")
            lifecycleScope.launch {
                val reply = GeminiBrain.processInput(applicationContext, cmd)
                appendLog("AURA: $reply")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AuraForegroundService.BROADCAST_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        refreshStatusUI()
    }

    override fun onPause() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun refreshStatusUI() {
        val running = AuraForegroundService.isRunning
        if (running) {
            tvServiceStatus.text = "Status: AKTIF 24/7 (Mendengarkan Suaramu)"
            btnToggle247.text = "MATIKAN"
        } else {
            tvServiceStatus.text = "Status: Nonaktif (Siap Dinyalakan)"
            btnToggle247.text = "NYALAKAN 24/7"
        }

        val micOk = if (hasMicPermission()) "OK" else "Belum"
        val overlayOk = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) "OK" else "Belum"
        val accOk = if (AuraAccessibilityService.isEnabled()) "OK" else "Belum"
        val notifOk = if (isNotificationListenerEnabled()) "OK" else "Belum"
        val batOk = if (isIgnoringBatteryOptimizations()) "OK" else "Belum"

        tvPermissionSummary.text =
            "Mikrofon: $micOk, Floating: $overlayOk, Kontrol Layar: $accOk, Notifikasi: $notifOk, Baterai: $batOk"
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllBasicPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, AuraNotificationListener::class.java)
        return flat.contains(cn.flattenToString())
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun appendLog(line: String) {
        val current = tvChatLog.text.toString()
        val sep = System.lineSeparator()
        val updated = if (current.isEmpty()) line else "$current$sep$line"
        tvChatLog.text = if (updated.length > 4000) updated.takeLast(4000) else updated
    }
}
