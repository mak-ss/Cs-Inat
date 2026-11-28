package com.Blockades

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

open class BaseTurkishProvider : MainAPI() {

    /** Alt sınıflar override eder. DomainResolver anahtarı. */
    open val siteKey: String = ""

    protected fun ensureLiveDomain() {
        runCatching {
            val live = DomainResolver.getLiveDomain(siteKey)
            if (!live.isNullOrBlank()) {
                mainUrl = live.trimEnd('/')
            }
        }
    }

    protected fun rewriteToLiveDomain(url: String): String {
        if (url.isBlank()) return url
        val live = runCatching { DomainResolver.getLiveDomain(siteKey) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: mainUrl
        val liveHost = live.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return Regex("""^https?://[^/]+""").replace(url) { "https://$liveHost" }
    }

    protected suspend fun getDoc(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document {
        val h = if (referer != null) headers + ("Referer" to referer) else headers
        return app.get(url, headers = h).document
    }
}
