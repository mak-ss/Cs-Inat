package com.Blockades

import org.jsoup.nodes.Element

/**
 * SuperFilmGeldi sayfalarındaki kartlardan poster adresi çıkarır.
 * Tema, tembel yükleme için data-src / data-lazy-src / srcset kullanıyor;
 * bunların hiçbiri yoksa doğrudan src'ye düşer.
 */
object SuperFilmGeldiResim {

    /** Bir kart (a.group) veya bölüm bağlantısındaki ilk img'den poster adresi döndürür. */
    fun kart(kutu: Element): String? {
        val img = kutu.selectFirst("img") ?: return null

        // Öncelik sırası: gerçek kaynak öznitelikleri, sonra src.
        val adaylar = sequenceOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-srcset"),
            img.attr("srcset"),
            img.attr("src")
        ).map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("data:") }

        val ham = adaylar.firstOrNull() ?: return null
        // srcset ise ilk adayı al ("url 1x, url2 2x" biçiminde)
        val temiz = if (ham.contains(","))
            ham.substringBefore(",").trim().substringBefore(" ")
        else
            ham.substringBefore(" ")

        if (temiz.startsWith("http://") || temiz.startsWith("https://")) return temiz

        val taban = kutu.ownerDocument()?.baseUri()?.let {
            Regex("""^(https?://[^/]+)""").find(it)?.groupValues?.get(1)
        } ?: return null

        return taban + (if (temiz.startsWith("/")) temiz else "/$temiz")
    }
}
