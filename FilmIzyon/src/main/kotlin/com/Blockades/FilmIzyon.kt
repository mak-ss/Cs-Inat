// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır. Krapotor'ın kodu gelistiriliyor
package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FilmIzyon : MainAPI() {
    override var mainUrl              = "https://www.filmizyon.net"
    override var name                 = "FilmIzyon"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}"                   to "Yeni Filmler",
        "${mainUrl}/tur/aile-filmleri-izle"        to "Aile",
        "${mainUrl}/tur/aksiyon-filmleri-izle"     to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmleri-izle"   to "Animasyon",
        "${mainUrl}/tur/belgesel-izle"             to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu-filmleri-izle" to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi-filmleri-izle"   to "Biyografi",
        "${mainUrl}/tur/dram-filmleri-izle"        to "Dram",
        "${mainUrl}/tur/fantastik-filmler-izle"    to "Fantastik",
        "${mainUrl}/tur/gerilim-filmleri-izle"     to "Gerilim",
        "${mainUrl}/tur/gizem-filmleri-izle"       to "Gizem",
        "${mainUrl}/tur/komedi-filmleri-izle"      to "Komedi",
        "${mainUrl}/tur/korku-filmleri-izle"       to "Korku",
        "${mainUrl}/tur/macera-filmleri-izle"      to "Macera",
        "${mainUrl}/tur/muzik-filmleri-izle"       to "Müzik",
        "${mainUrl}/tur/polisiye-filmler-izle"     to "Polisiye",
        "${mainUrl}/tur/romantik-filmler-izle"     to "Romantik",
        "${mainUrl}/tur/savas-filmleri-izle"       to "Savaş",
        "${mainUrl}/tur/spor-filmleri-izle"        to "Spor",
        "${mainUrl}/tur/suc-filmleri-izle"         to "Suç",
        "${mainUrl}/tur/tarih-filmleri-izle"       to "Tarih",
        "${mainUrl}/tur/yerli-film-izle"            to "Yerli"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        val home     = document.select("div.col-lg-3.col-6.poster-container").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("h2")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("source")?.attr("data-srcset"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.col-lg-3.col-6").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h2")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("source")?.attr("data-srcset"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1.page-title")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("picture img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("picture source")?.attr("data-srcset"))
            ?: fixUrlNull(document.selectFirst("picture img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description     = document.selectFirst("article.text-white")?.text()?.trim()
        val year            = document.selectFirst("div.d-flex.flex-column.text-nowrap a")?.text()?.trim()?.toIntOrNull()
        val tags            = document.select("div.pb-0 a.btn-warning").map { it.text() }
        val rating          = document.selectFirst("div.d-flex.flex-column.text-nowrap strong.text-danger")?.text()?.trim()
        val duration        = document.selectFirst("div.table-responsive table > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(2) > div:nth-child(1) > strong")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val fragmanElement = document
            .select("div.filmsayfala a")
            .firstOrNull { it.text().equals("fragman", ignoreCase = true) }
        val fragmanHref: String? = fragmanElement?.attr("href")
        Log.d("kraptor_","fragmanHref = $fragmanHref")

        var trailer: String? = null
        if (!fragmanHref.isNullOrBlank()) {
            val fragmancek = app.get(fragmanHref).document
            trailer = fragmancek.selectFirst("iframe")?.attr("src")
            Log.d("kraptor_","trailer = $trailer")
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.score = Score.from10(rating)
            this.duration        = duration
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("a img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_${this.name}", "data = $data")
        val document = app.get(data).document

        // ============================================================
        // 1) Sayfadaki iframe'i bul (src veya data-src)
        // ============================================================
        val iframe = document
            .selectFirst("iframe")
            ?.let { el ->
                val src = el.attr("src")
                if (src.isNotBlank()) src else el.attr("data-src")
            }
            .orEmpty()
        Log.d("kraptor_${this.name}", "iframe = $iframe")

        // ============================================================
        // 2) İframe varsa extractor'a gönder (m3u8 buradan çıkar)
        // ============================================================
        if (iframe.isNotBlank() && iframe.startsWith("http")) {
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        // ============================================================
        // 3) Sayfa HTML'inin içinde doğrudan m3u8 linki var mı?
        //    (JSON / script / data-* içinde gömülü olabilir)
        // ============================================================
        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        val html = document.html()
        m3u8Regex.findAll(html).forEach { match ->
            val m3u8 = match.value
                .replace("\\/", "/")
                .replace("&amp;", "&")
            Log.d("kraptor_${this.name}", "m3u8 bulundu = $m3u8")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }
}
