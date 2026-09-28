package com.Blockades

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class BirAsyaDiziExtractor {

    fun extractIframeUrl(htmlContent: String): String? {
        if (htmlContent.isBlank()) {
            println("HATA: Gönderilen HTML içeriği boş.")
            return null
        }

        val doc: Document = Jsoup.parse(htmlContent)

        // 1. Doğrudan 'src' özniteliğine sahip iframe etiketini ara
        val iframeElement = doc.select("iframe[src]").firstOrNull()
        if (iframeElement != null) {
            val src = iframeElement.attr("abs:src").ifEmpty { iframeElement.attr("src") }
            if (src.isNotBlank()) {
                return src
            }
        }

        // 2. Lazy-load (Gecikmeli yükleme) ile çalışan iframe'ler için 'data-src' kontrolü
        val lazyIframe = doc.select("iframe[data-src]").firstOrNull()
        if (lazyIframe != null) {
            val dataSrc = lazyIframe.attr("data-src")
            if (dataSrc.isNotBlank()) {
                return dataSrc
            }
        }

        // 3. Iframe bulunamadıysa log düş ve null dön
        println("HATA: Sayfada hiç iframe bulunamadı! Sayfa yapısı değişmiş veya içerik JavaScript ile dinamik yükleniyor olabilir.")
        return null
    }
}
