package com.aura.assistant

import android.content.Context
import android.content.SharedPreferences

object AuraConfig {
    private const val PREF_NAME = "aura_os_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_ACTIVE_247 = "active_247"
    private const val KEY_WAKE_WORD = "require_wake_word"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API, "")?.trim() ?: ""

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun is247Active(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE_247, false)

    fun set247Active(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE_247, active).apply()
    }

    fun isWakeWordRequired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_WORD, false)

    fun setWakeWordRequired(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_WORD, required).apply()
    }
}
