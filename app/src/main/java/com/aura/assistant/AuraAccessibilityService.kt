package com.aura.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AuraAccessibilityService : AccessibilityService() {

    data class AutomationTask(
        val targetApp: String,
        val recipientOrOrigin: String = "",
        val destinationOrText: String = "",
        val extraInfo: String = "",
        var step: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    companion object {
        @Volatile
        var instance: AuraAccessibilityService? = null

        @Volatile
        var pendingTask: AutomationTask? = null

        fun queueTask(task: AutomationTask) {
            pendingTask = task
        }

        fun isEnabled(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = pendingTask ?: return
        if (System.currentTimeMillis() - task.createdAt > 45_000L) {
            pendingTask = null
            return
        }

        val pkg = event?.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return

        when (task.targetApp) {
            "WHATSAPP" -> if (pkg.contains("whatsapp")) handleWhatsApp(root, task)
            "KAI" -> if (pkg.contains("kai")) handleKaiAccess(root, task)
            "TRAVELOKA" -> if (pkg.contains("traveloka")) handleTraveloka(root, task)
            "GOJEK" -> if (pkg.contains("gojek")) handleGojek(root, task)
            "YOUTUBE" -> if (pkg.contains("youtube")) handleYouTube(root, task)
        }
    }

    override fun onInterrupt() {
    }

    private fun handleWhatsApp(root: AccessibilityNodeInfo, task: AutomationTask) {
        val sendNode = findNodeByDescOrText(root, listOf("kirim", "send"))
        if (sendNode != null && task.destinationOrText.isNotBlank()) {
            val edit = findFirstEditable(root)
            if (edit != null && edit.text.isNullOrBlank()) {
                setNodeText(edit, task.destinationOrText)
            }
            handler.postDelayed({
                clickNode(sendNode)
                pendingTask = null
            }, 500)
            return
        }

        if (task.step == 0 && task.recipientOrOrigin.isNotBlank()) {
            val searchBtn = findNodeByDescOrText(root, listOf("cari", "search"))
            if (searchBtn != null) {
                clickNode(searchBtn)
                task.step = 1
                return
            }
        }
        if (task.step == 1) {
            val searchInput = findFirstEditable(root)
            if (searchInput != null) {
                setNodeText(searchInput, task.recipientOrOrigin)
                task.step = 2
                handler.postDelayed({
                    val contactMatch = rootInActiveWindow?.let {
                        findNodeByDescOrText(it, listOf(task.recipientOrOrigin))
                    }
                    if (contactMatch != null) {
                        clickNode(contactMatch)
                        task.step = 3
                    }
                }, 900)
            }
            return
        }
        if (task.step == 3 && task.destinationOrText.isNotBlank()) {
            val msgBox = findFirstEditable(root)
            if (msgBox != null) {
                setNodeText(msgBox, task.destinationOrText)
                handler.postDelayed({
                    val btnSend = rootInActiveWindow?.let {
                        findNodeByDescOrText(it, listOf("kirim", "send"))
                    }
                    if (btnSend != null) {
                        clickNode(btnSend)
                    }
                    pendingTask = null
                }, 600)
            }
        }
    }

    private fun handleKaiAccess(root: AccessibilityNodeInfo, task: AutomationTask) {
        if (containsPaymentKeyword(root)) {
            pendingTask = null
            return
        }
        if (task.step == 0) {
            val trainMenu = findNodeByDescOrText(root, listOf("antar kota", "kereta", "lokal"))
            if (trainMenu != null) {
                clickNode(trainMenu)
                task.step = 1
                return
            }
        }
        if (task.step == 1 && task.destinationOrText.isNotBlank()) {
            val edit = findFirstEditable(root)
            if (edit != null) {
                setNodeText(edit, task.destinationOrText)
                pendingTask = null
            }
        }
    }

    private fun handleTraveloka(root: AccessibilityNodeInfo, task: AutomationTask) {
        if (containsPaymentKeyword(root)) {
            pendingTask = null
            return
        }
        if (task.step == 0) {
            val keywords = when (task.extraInfo.lowercase()) {
                "hotel" -> listOf("hotel")
                "kereta" -> listOf("kereta api", "trains")
                "bus" -> listOf("bus", "travel")
                else -> listOf("tiket pesawat", "flights", "pesawat")
            }
            val menu = findNodeByDescOrText(root, keywords)
            if (menu != null) {
                clickNode(menu)
                task.step = 1
                return
            }
        }
        if (task.step == 1 && task.destinationOrText.isNotBlank()) {
            val edit = findFirstEditable(root)
            if (edit != null) {
                setNodeText(edit, task.destinationOrText)
                pendingTask = null
            }
        }
    }

    private fun handleGojek(root: AccessibilityNodeInfo, task: AutomationTask) {
        if (containsPaymentKeyword(root)) {
            pendingTask = null
            return
        }
        if (task.step == 0) {
            val svc = when (task.extraInfo.lowercase()) {
                "gocar", "mobil" -> listOf("gocar")
                "gofood", "makan" -> listOf("gofood")
                else -> listOf("goride", "motor")
            }
            val menu = findNodeByDescOrText(root, svc)
            if (menu != null) {
                clickNode(menu)
                task.step = 1
                return
            }
        }
        if (task.step == 1 && task.destinationOrText.isNotBlank()) {
            val edit = findFirstEditable(root)
            if (edit != null) {
                setNodeText(edit, task.destinationOrText)
                pendingTask = null
            }
        }
    }

    private fun handleYouTube(root: AccessibilityNodeInfo, task: AutomationTask) {
        if (task.step == 0 && task.destinationOrText.isNotBlank()) {
            handler.postDelayed({
                val r = rootInActiveWindow ?: return@postDelayed
                val videoNode = findFirstClickableVideo(r, task.destinationOrText)
                if (videoNode != null) {
                    clickNode(videoNode)
                }
                pendingTask = null
            }, 1400)
            task.step = 1
        }
    }

    fun performScreenCommand(command: String, targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return when (command.lowercase()) {
            "back", "kembali" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home", "beranda" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "scroll_down", "gulir_bawah" -> {
                val scrollable = findScrollable(root)
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            }
            "scroll_up", "gulir_atas" -> {
                val scrollable = findScrollable(root)
                scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
            }
            "click", "klik" -> {
                val node = findNodeByDescOrText(root, listOf(targetText))
                if (node != null) clickNode(node) else false
            }
            "type", "ketik" -> {
                val edit = findFirstEditable(root)
                if (edit != null) setNodeText(edit, targetText) else false
            }
            else -> false
        }
    }

    private fun containsPaymentKeyword(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val t = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        val lower = t.lowercase()
        if (lower.contains("bayar sekarang") || lower.contains("pay now") || lower.contains("konfirmasi pembayaran")) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsPaymentKeyword(node.getChild(i))) return true
        }
        return false
    }

    private fun findNodeByDescOrText(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val combined = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
        for (kw in keywords) {
            if (kw.isNotBlank() && combined.contains(kw.lowercase())) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByDescOrText(node.getChild(i), keywords)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstClickableVideo(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((node.isClickable && desc.contains("menit")) || (node.isClickable && desc.contains("detik"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findFirstClickableVideo(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
