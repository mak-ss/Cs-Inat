package com.Blockades

/** Reklam URL'lerini filtrelemek için basit politika. */
object AdPolicy {

    private val adHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adsystem.com",
        "popads.net",
        "propellerads.com",
        "onclickads.net",
        "adsterra.com"
    )

    fun isAdvertisingUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return adHosts.any { lower.contains(it) }
    }
}
