// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import org.json.JSONObject
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
        "${mainUrl}/diziler/yemek-dizi/"                to "Yemek Dizi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page/$page/").document
        val home     = document.select("div.frag-k.yedi.yan").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.attr("title") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.frag-k").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.attr("title") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.dizi-bilgi h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.dizi-bilgi .afis img")?.attr("data-src")
                ?: document.selectFirst("div.dizi-bilgi .afis img")?.attr("src")
        )

        val description = document.selectFirst("ol#t2 .aciklama")?.text()?.trim()
            ?: document.selectFirst("div.dizi-bilgi .aciklama")?.text()?.trim()

        val year = document.selectFirst("ol#t2 h2 span")?.text()?.trim()
            ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

        val tags = document.select("ol#t2 .alt b, div.dizi-bilgi .detay li span")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        val rating = document.selectFirst("div.dizi-bilgi .puan b")?.text()?.trim()
            ?: document.selectFirst("ol#t2 .bilgi span i.fa-imdb")?.parent()?.text()?.trim()

        val recommendations = document.select("div.sag-vliste li").mapNotNull { it.toRecommendationResult() }

        val episodes = document.select("ol#s0 li[id^=eb]").mapNotNull { bolum ->
            val epHref = fixUrlNull(bolum.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epName = bolum.selectFirst("span.blm")?.text()?.trim()
                ?: bolum.selectFirst("span.dizi-isim")?.text()?.trim()
                ?: bolum.selectFirst("a")?.attr("title")?.trim()
            val epEpisode = bolum.selectFirst("span.blm")?.text()?.trim()
                ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            newEpisode(epHref) {
                this.episode = epEpisode
                this.name = epName
            }
        }

        return if (episodes.isEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, listOf(newEpisode(url) { this.name = title })) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
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
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("span.baslik")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "loadLinks data » $data")
        val document = app.get(data).document
        var found = false

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        // ========== 1. ok.ru videoembed linkini doğrudan API ile çöz ==========
        val iframeSrc = document.selectFirst("iframe#Vidpplayera")?.attr("vdo-src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("#vast iframe")?.attr("vdo-src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("iframe[vdo-src]")?.attr("vdo-src")?.takeIf { it.isNotBlank() }

        if (iframeSrc != null) {
            val fullUrl = fixUrlNull(iframeSrc)!!
            Log.d("kraptor_$name", "okru embed url » $fullUrl")

            val videoId = Regex("""/videoembed/(\d+)""").find(fullUrl)?.groupValues?.get(1)
                ?: Regex("""/video/(\d+)""").find(fullUrl)?.groupValues?.get(1)

            if (videoId != null) {
                val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
                try {
                    val jsonText = app.get(
                        apiUrl,
                        referer = "https://ok.ru/videoembed/$videoId",
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "application/json, text/plain, */*"
                        )
                    ).text

                    Log.d("kraptor_$name", "okru api response » ${jsonText.take(300)}")

                    // Cevap formatı: callbackFunc({...})  veya  {...}
                    val cleanJson = jsonText
                        .substringAfter("callbackFunc(", jsonText)
                        .substringBeforeLast(")")

                    val json = JSONObject(cleanJson)
                    val videos = json.optJSONArray("videos")

                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            val vUrl = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val vName = v.optString("name").lowercase()

                            val quality = when {
                                vName.contains("1080") -> Qualities.P1080.value
                                vName.contains("720")  -> Qualities.P720.value
                                vName.contains("480")  -> Qualities.P480.value
                                vName.contains("360")  -> Qualities.P360.value
                                vName.contains("240")  -> Qualities.P240.value
                                else -> Qualities.Unknown.value
                            }

                            val isM3u8 = vUrl.contains(".m3u8", ignoreCase = true)

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = vUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://ok.ru/"
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "User-Agent" to ua,
                                        "Referer" to "https://ok.ru/"
                                    )
                                }
                            )
                            Log.d("kraptor_$name", "okru link » $vUrl | q=$quality | m3u8=$isM3u8")
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("kraptor_$name", "okru parse error: ${e.message}")
                }
            } else {
                Log.w("kraptor_$name", "okru id bulunamadı, loadExtractor fallback")
                if (loadExtractor(fullUrl, mainUrl, subtitleCallback, callback)) found = true
            }
        }

        // ========== 2. okru başarısızsa genel iframe fallback ==========
        if (!found) {
            document.select("iframe").forEach { iframe ->
                val link = fixUrlNull(
                    iframe.attr("src").takeIf { it.isNotBlank() && it != "#!" && !it.startsWith("about:") }
                        ?: iframe.attr("vdo-src").takeIf { it.isNotBlank() }
                        ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                )
                if (link != null &&
                    !link.contains("google.com/url") &&
                    !link.contains("googleads") &&
                    !link.contains("doubleclick")
                ) {
                    Log.d("kraptor_$name", "fallback iframe » $link")
                    if (loadExtractor(link, mainUrl, subtitleCallback, callback)) found = true
                }
            }
        }

        // ========== 3. Sayfada doğrudan m3u8 / mp4 var mı? ==========
        if (!found) {
            Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)(\?[^\s"'<>]*)?""").findAll(document.html()).forEach { m ->
                val directUrl = m.value
                Log.d("kraptor_$name", "direct » $directUrl")
                val isM3u8 = directUrl.contains(".m3u8", ignoreCase = true)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = directUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to ua)
                    }
                )
                found = true
            }
        }

        if (!found) Log.w("kraptor_$name", "Hiçbir link bulunamadı!")
        return found
    }
}
