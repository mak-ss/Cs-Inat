package com.Blockades

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private const val PREFS = "bigbull_dizipal"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun rowEnabled(rowName: String): Boolean =
        prefs?.getBoolean("row_$rowName", true) ?: true

    fun setRowEnabled(rowName: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean("row_$rowName", enabled)?.apply()
    }

    fun qualityAllowed(quality: Int): Boolean {
        val min = prefs?.getInt("min_quality", 0) ?: 0
        return quality == com.lagradost.cloudstream3.utils.Qualities.Unknown.value || quality >= min
    }

    fun setMinQuality(quality: Int) {
        prefs?.edit()?.putInt("min_quality", quality)?.apply()
    }

    fun subtitleAllowed(lang: String): Boolean {
        val disabled = prefs?.getStringSet("disabled_subs", emptySet()) ?: emptySet()
        return lang !in disabled
    }

    fun setSubtitleAllowed(lang: String, allowed: Boolean) {
        val current = (prefs?.getStringSet("disabled_subs", emptySet()) ?: emptySet()).toMutableSet()
        if (allowed) current.remove(lang) else current.add(lang)
        prefs?.edit()?.putStringSet("disabled_subs", current)?.apply()
    }

    fun showDialog(context: Context, title: String, rows: List<String>) {
        // Basit ayar diyaloğu; satır aç/kapa ve minimum kalite seçimi
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle(title)
        val items = rows.map { "$it ${if (rowEnabled(it)) "✓" else "✗"}" }.toTypedArray()
        builder.setItems(items) { _, which ->
            val row = rows[which]
            setRowEnabled(row, !rowEnabled(row))
        }
        builder.setPositiveButton("Kapat", null)
        builder.show()
    }
}
