package com.Blockades

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

/**
 * Türkçe sağlayıcılar için ortak taban.
 * - ensureLiveDomain(): DomainResolver üzerinden canlı domaini mainUrl'e yazar.
 * - rewriteToLiveDomain(url): Eski domaini canlı domainle değiştirir.
 * - getDoc(url, referer): Jsoup Document döndürür.
 */
open class BaseTurkishProvider : MainAPI() {

    /** Alt sınıflar override eder. */
    protected open val domainKey: String get() = siteKey

    /** DomainResolver'a kayıtlı canlı domaini mainUrl'e uygular. */
    protected fun ensureLiveDomain() {
        runCatching {
            val live = DomainResolver.getLiveDomain(domainKey)
            if (!live.isNullOrBlank()) {
                mainUrl = live.trimEnd('/')
            }
        }
    }

    /** Verilen URL'deki eski domaini canlı domain ile değiştirir. */
    protected fun rewriteToLiveDomain(url: String): String {
        if (url.isBlank()) return url
        val live = runCatching { DomainResolver.getLiveDomain(domainKey) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: mainUrl
        val liveHost = live.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return Regex("""^https?://[^/]+""").replace(url) { "https://$liveHost" }
    }

    /** Jsoup Document döndürür. */
    protected suspend fun getDoc(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document {
        val h = if (referer != null) headers + ("Referer" to referer) else headers
        return app.get(url, headers = h).document
    }
}
