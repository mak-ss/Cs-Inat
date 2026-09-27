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
        "${mainUrl}/platform/netflix"            to "Netflix",
        "${mainUrl}/platform/exxen"              to "Exxen",
        "${mainUrl}/platform/prime-video"        to "Prime&Video",
        "${mainUrl}/platform/disney-plus"        to "Disney Plus",
        "${mainUrl}/platform/hbo-max"            to "HBO-MAX",
        "${mainUrl}/platform/tabii"              to "Tabii",
        "${mainUrl}/platform/gain"               to "Gain",
        "${mainUrl}/platform/apple-tv"           to "Apple Tv",
        "${mainUrl}/platform/hulu"               to "Hulu",
    )

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

        val home = document.select("div.grid > a.group, div.flex-shrink-0 > a.group").mapNotNull { el ->
            el.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/arama?q=${query}"
        val document = app.get(searchUrl).document

        return document.select("div.grid > a.group").mapNotNull { el ->
            el.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // JSON-LD'den film/dizi bilgilerini almaya çalış
        val jsonLd = document.select("script[type='application/ld+json']")
            .mapNotNull { it.data() }
            .firstOrNull { it.contains("\"@type\":\"Movie\"") || it.contains("\"@type\":\"TVSeries\"") }

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim()
            ?: jsonLd?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: jsonLd?.let { Regex(""""image"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.let { p -> fixUrl(p) } }

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: jsonLd?.let { Regex(""""description"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }

        return if (url.contains("/diziler/")) {
            data class EpData(val href: String, val name: String?, val season: Int, val episode: Int?)

            val epDataList = document.select("a[href*='/bolumler/']").mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.selectFirst("p.text-\\[12px\\]")?.text()?.trim()
                    ?: el.selectFirst("p.font-semibold")?.text()?.trim()
                    ?: el.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = el.selectFirst("p.text-\\[11px\\].text-\\[\\#aaa\\]")?.text()?.trim()
                    ?: el.selectFirst("p.text-\\[\\#aaa\\]")?.text()?.trim()
                EpData(epHref, epName, season, episode)
            }.distinctBy { it.href }

            val episodes = epDataList.map { data ->
                newEpisode(data.href) {
                    this.name = data.name
                    this.season = data.season
                    this.episode = data.episode
                }
            }

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

        // Öncelik sırası: iframe[src] > og:video > og:video:secure_url > JSON-LD embedUrl
        val embedUrl = document.selectFirst("iframe[src]")?.attr("src")
            ?.takeIf { it.contains("player") || it.contains("embed") || it.contains("video") }
            ?: document.selectFirst("meta[property='og:video']")?.attr("content")
            ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")
            ?: document.select("script[type='application/ld+json']")
                .mapNotNull { it.data() }
                .firstOrNull { it.contains("\"embedUrl\"") }
                ?.let { Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        if (embedUrl.isNullOrBlank()) {
            Log.d("DPO", "embedUrl bulunamadı, doğrudan video kaynağı aranıyor...")
            val videoSrc = document.selectFirst("video source[src]")?.attr("src")
                ?: document.selectFirst("video[src]")?.attr("src")
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

        val fixedEmbedUrl = fixUrl(embedUrl)
        Log.d("DPO", "embedUrl » $fixedEmbedUrl")

        // videoplays.cfd için özel extractor
        if (fixedEmbedUrl.contains("videoplays.cfd")) {
            try {
                VideoplaysCfd().getUrl(fixedEmbedUrl, data, subtitleCallback, callback)
                Log.d("DPO", "VideoplaysCfd extractor çağrıldı.")
                return true
            } catch (e: Exception) {
                Log.d("DPO", "VideoplaysCfd extractor hatası » ${e.message}")
            }
        }

        // Yedek: Embed sayfasını çekip regex ile m3u8/mp4 bul
        try {
            val embedResp = app.get(
                fixedEmbedUrl,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Origin" to mainUrl
                )
            ).text

            Log.d("DPO", "embed response length » ${embedResp.length}")

            val patterns = listOf(
                Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)"""),
                Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""source\s+src=["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                val match = pattern.find(embedResp)?.groupValues?.get(1)
                if (!match.isNullOrBlank()) {
                    Log.d("DPO", "Regex ile videoUrl bulundu » $match")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = match,
                            type = if (match.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = fixedEmbedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d("DPO", "embed fetch hatası » ${e.message}")
        }

        // Son çare: loadExtractor
        try {
            if (loadExtractor(fixedEmbedUrl, "$mainUrl/", subtitleCallback, callback)) {
                Log.d("DPO", "loadExtractor başarılı.")
                return true
            }
        } catch (e: Exception) {
            Log.d("DPO", "loadExtractor hatası » ${e.message}")
        }

        return false
    }
}
