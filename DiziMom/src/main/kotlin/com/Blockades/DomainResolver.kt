package com.Blockades

import android.content.Context

/**
 * Basit domain çözümleyici.
 * Gerçek uygulamada SharedPreferences / uzak API ile canlı domain çekilebilir.
 * Burada varsayılan olarak sabit domain döndürür; istenirse setLiveDomain ile güncellenebilir.
 */
object DomainResolver {

    private val liveDomains = mutableMapOf<String, String>()

    private val defaults = mapOf(
        "DiziMom" to "https://ddizimom.net"
    )

    fun initialize(context: Context) {
        // İleride: SharedPreferences'tan yükle
        defaults.forEach { (k, v) -> liveDomains.putIfAbsent(k, v) }
    }

    fun setLiveDomain(key: String, url: String) {
        liveDomains[key] = url.trimEnd('/')
    }

    fun getLiveDomain(key: String): String? = liveDomains[key] ?: defaults[key]
}
