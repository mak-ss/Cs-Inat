// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BirAsyaDizi : MainAPI() {
    override var mainUrl              = "https://www.birasyadizi.cx"
    override var name                 = "BirAsyaDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama)

    private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/aile-dizi/"                 to "Aile dizi",
        "${mainUrl}/diziler/aksiyon-dizi/"              to "Aksiyon Dizi",
        "${mainUrl}/diziler/anime/"                     to "Anime",
        "${mainUrl}/diziler/arkadaslik-dizi/"           to "Arkadaşlık Dizi",
        "${mainUrl}/diziler/askeri/"                    to "Askeri",
        "${mainUrl}/diziler/belgesel/"                  to "Belgesel",
        "${mainUrl}/diziler/bilim-kurgu/"               to "Bilim Kurgu",
        "${mainUrl}/diziler/cin-dizileri/"              to "Çin Dizileri",
        "${mainUrl}/diziler/dedektif-dizi/"             to "Dedektif Dizi",
        "${mainUrl}/diziler/dogaustu-dizi/"             to "Doğaüstü Dizi",
        "${mainUrl}/diziler/dokumanter/"                to "Dökümanter",
        "${mainUrl}/diziler/dram-dizi/"                 to "Dram Dizi",
        "${mainUrl}/diziler/endonezya-dizileri/"        to "Endonezya Dizileri",
        "${mainUrl}/diziler/fantastik-dizi/"            to "Fantastik Dizi",
        "${mainUrl}/diziler/filipinler/"                to "Filipinler",
        "${mainUrl}/diziler/genclik-dizileri/"          to "Gençlik dizileri",
        "${mainUrl}/diziler/gerilim-dizi/"              to "Gerilim Dizi",
        "${mainUrl}/diziler/gizem-dizi/"                to "Gizem Dizi",
        "${mainUrl}/diziler/guney-kore/"                to "Güney Kore",
        "${mainUrl}/diziler/hindistan/"                 to "Hindistan",
        "${mainUrl}/diziler/hong-kong/"                 to "Hong Kong",
        "${mainUrl}/diziler/hukuk-dizi/"                to "Hukuk Dizi",
        "${mainUrl}/diziler/is-dizi/"                   to "İş Dizi",
        "${mainUrl}/diziler/japon-dizileri/"            to "Japon Dizileri",
        "${mainUrl}/diziler/komedi-dizi/"               to "Komedi Dizi",
        "${mainUrl}/diziler/korku-dizi/"                to "Korku Dizi",
        "${mainUrl}/diziler/macera-dizi/"               to "Macera Dizi",
        "${mainUrl}/diziler/malezya/"                   to "Malezya",
        "${mainUrl}/diziler/melodram-dizi/"             to "Melodram Dizi",
        "${mainUrl}/diziler/muzik-dizi/"                to "Müzik Dizi",
        "${mainUrl}/diziler/okul-dizi/"                 to "Okul Dizi",
        "${mainUrl}/diziler/pakistan/"                  to "Pakistan",
        "${mainUrl}/diziler/politik-dizi/"              to "Politik dizi",
        "${mainUrl}/diziler/psikolojik/"                to "Psikolojik",
        "${mainUrl}/diziler/reality-show/"              to "Reality Show",
        "${mainUrl}/diziler/romantik-dizi/"             to "Romantik Dizi",
        "${mainUrl}/diziler/savas-dizi/"                to "Savaş Dizi",
        "${mainUrl}/diziler/savas-sanatlari/"           to "Savaş Sanatları",
        "${mainUrl}/diziler/singapur/"                  to "Singapur",
        "${mainUrl}/diziler/singapur-dizileri/"         to "Singapur Dizileri",
        "${mainUrl}/diziler/sitkom-dizi/"               to "Sitkom Dizi",
        "${mainUrl}/diziler/sorusturma-dizi/"           to "Soruşturma Dizi",
        "${mainUrl}/diziler/spor-dizi/"                 to "Spor Dizi",
        "${mainUrl}/diziler/suc-dizi/"                  to "Suç Dizi",
        "${mainUrl}/diziler/tarihi-dizi/"               to "Tarihi Dizi",
        "${mainUrl}/diziler/tayland-dizileri/"          to "Tayland dizileri",
        "${mainUrl}/diziler/tayvan-diileri/"            to "Tayvan Diileri",
        "${mainUrl}/diziler/tip-dizi/"                  to "Tıp Dizi",
        "${mainUrl}/diziler/trajedi-dizi/"              to "Trajedi Dizi",
        "${mainUrl}/diziler/tv-show/"                   to "TV Show",
        "${mainUrl}/diziler/vietnam-dizileri/"          to "Vietnam Dizileri",
        "${mainUrl}/diziler/yasam-dizi/"                to "Yaşam Dizi",
        "${mainUrl}/diziler/yemek-dizi/"                to "Yemek Dizi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page/$page/", headers = mainHeaders).document
        val home     = document.select("div.frag-k").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.attr("title")?.ifEmpty { null } ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", headers = mainHeaders).document
        return document.select("div.frag-k").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.attr("title")?.ifEmpty { null } ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mainHeaders).document

        val title = document.selectFirst("div.tab-icerik img")?.attr("title") ?: return null
        val img = document.selectFirst("div.tab-icerik img")
        val poster = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })
        val description = document.selectFirst("div.aciklama div.scroll-liste")?.text()?.trim()
        val year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("ol.gizli li a").map { it.text() }
        val rating = document.selectFirst("span.tum-gor")?.text()?.trim()
        val recommendations = document.select("div.sag-vliste li").mapNotNull { it.toRecommendationResult() }

        val episodes = document.select("li.szn").mapNotNull { bolum ->
            val epName = bolum.selectFirst("div.baslik a")?.text()?.trim()
            val epHref = fixUrlNull(bolum.selectFirst("div.resim a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = epHref
                .substringBeforeLast("-bolum")
                .substringAfterLast("-")
                .toIntOrNull()

            newEpisode(epHref) {
                this.episode = epEpisode
                this.name = epName
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AsianDrama, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("span.baslik")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val img       = this.selectFirst("a img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mainHeaders).document
        val iframe = document.selectFirst("iframe") ?: return false

        val iframeVid = fixUrlNull(
            iframe.attr("vdo-src").ifEmpty { 
                iframe.attr("src").ifEmpty { 
                    iframe.attr("data-src") 
                } 
            }
        ) ?: return false

        Log.d("kraptor_$name", "iframeVid -> $iframeVid")

        return loadExtractor(iframeVid, "$mainUrl/", subtitleCallback, callback)
    }
}
