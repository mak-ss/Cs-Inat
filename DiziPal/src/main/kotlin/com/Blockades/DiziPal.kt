package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1432.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler"                     to "Yeni Filmler",
        "${mainUrl}/diziler"                     to "Son Eklenen Diziler",
        "${mainUrl}/bolumler"                    to "Yeni Bölümler",
        "${mainUrl}/populer"                     to "Popüler Filmler",
        "${mainUrl}/diziler?sort=popular"        to "Popüler Diziler",
    )

    // HTML'deki kart yapısını çözecek yardımcı fonksiyon
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.attr("title")?.replace(" izle", "")?.trim()
            ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/filmler/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}${if (request.data.contains("?")) "&" else "?"}page=${page}" else request.data
        val document = app.get(url).document

        // HTML'deki ana kart yapısına göre seçiciler güncellendi.
        // Öne Çıkanlar bölümü için `div.flex-shrink-0` > `a.group` yapısı kullanılıyor.
        // Diğer bölümler için `div.grid` > `a.group` yapısı kullanılıyor.
        val home = document.select("div.grid > a.group, div.flex-shrink-0 > a.group").mapNotNull { el ->
            el.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama URL'si ve seçicileri HTML yapısına göre güncellendi.
        val searchUrl = "${mainUrl}/arama?q=${query}"
        val document = app.get(searchUrl).document

        return document.select("div.grid > a.group").mapNotNull { el ->
            el.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }

        return if (url.contains("/diziler/")) {
            // Dizi bölümlerini HTML yapısına göre ayıklama
            val episodes = document.select("a[href*='/bolumler/']").mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.selectFirst("p.text-\\[12px\\]")?.text()?.trim() ?: el.text().trim()

                val season = Regex("""(\d+)\.\s*Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                
                // Bölüm adı genellikle "1. Sezon 1. Bölüm" formatındadır, ayrı bir isim yoksa bu metni kullanırız.
                val epName = el.selectFirst("p.text-\\[11px\\].text-\\[\\#aaa\\]")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.url }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DPO", "data » $data")
        val document = app.get(data).document

        // Film/Dizi sayfasında iframe veya doğrudan video kaynağı arayalım.
        val iframeUrl = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("meta[property='og:video']")?.attr("content")
            ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

        if (iframeUrl.isNullOrBlank()) {
            Log.d("DPO", "iframe veya og:video bulunamadı.")
            // Belki de doğrudan bir video etiketi vardır?
            val videoSrc = document.selectFirst("video source[src]")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                 Log.d("DPO", "Doğrudan video kaynağı bulundu: $videoSrc")
                 callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fixUrl(videoSrc),
                        type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            return false
        }

        val embedUrl = fixUrl(iframeUrl)
        Log.d("DPO", "embedUrl » $embedUrl")

        // 1. Yöntem: Embed sayfasını çekip içindeki m3u8/mp4 linklerini regex ile bulma
        try {
            val embedResp = app.get(
                embedUrl,
                referer = "${mainUrl}/",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text

            val m3u8 = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(embedResp)?.groupValues?.get(1)
            val mp4 = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""").find(embedResp)?.groupValues?.get(1)
            val videoUrl = m3u8 ?: mp4

            if (videoUrl != null) {
                Log.d("DPO", "Regex ile videoUrl bulundu » $videoUrl")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.d("DPO", "embed fetch hatası » ${e.message}")
        }

        // 2. Yöntem: Eğer regex işe yaramazsa, harici bir extractor kullanmayı dene
        try {
            if (loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)) {
                Log.d("DPO", "loadExtractor başarılı.")
                return true
            }
        } catch (e: Exception) {
            Log.d("DPO", "loadExtractor hatası » ${e.message}")
        }

        return false
    }
}
