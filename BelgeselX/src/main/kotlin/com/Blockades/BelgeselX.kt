// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.*
import kotlinx.coroutines.*

class BelgeselX : MainAPI() {
    override var mainUrl = "https://belgeselx.com"
    override var name = "BelgeselX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Documentary)

    override val mainPage = mainPageOf(
        "${mainUrl}/konu/turk-tarihi-belgeselleri&page=" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri&page=" to "Tarih",
        "${mainUrl}/konu/seyehat-belgeselleri&page=" to "Seyahat",
        "${mainUrl}/konu/seri-belgeseller&page=" to "Seri",
        "${mainUrl}/konu/savas-belgeselleri&page=" to "Savaş",
        "${mainUrl}/konu/sanat-belgeselleri&page=" to "Sanat",
        "${mainUrl}/konu/psikoloji-belgeselleri&page=" to "Psikoloji",
        "${mainUrl}/konu/polisiye-belgeselleri&page=" to "Polisiye",
        "${mainUrl}/konu/otomobil-belgeselleri&page=" to "Otomobil",
        "${mainUrl}/konu/nazi-belgeselleri&page=" to "Nazi",
        "${mainUrl}/konu/muhendislik-belgeselleri&page=" to "Mühendislik",
        "${mainUrl}/konu/kultur-din-belgeselleri&page=" to "Kültür Din",
        "${mainUrl}/konu/kozmik-belgeseller&page=" to "Kozmik",
        "${mainUrl}/konu/hayvan-belgeselleri&page=" to "Hayvan",
        "${mainUrl}/konu/eski-tarih-belgeselleri&page=" to "Eski Tarih",
        "${mainUrl}/konu/egitim-belgeselleri&page=" to "Eğitim",
        "${mainUrl}/konu/dunya-belgeselleri&page=" to "Dünya",
        "${mainUrl}/konu/doga-belgeselleri&page=" to "Doğa",
        "${mainUrl}/konu/bilim-belgeselleri&page=" to "Bilim"
    )

    // HTML'deki JS'den alınan kaynak haritası
    // var srcMap = { '0':'new5','2':'new1','5':'new4','3':'new2','4':'new3' };
    private val sourceMap = mapOf(
        "0" to "new5",
        "2" to "new1",
        "5" to "new4",
        "3" to "new2",
        "4" to "new3"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home = document.select("div.gen-movie-contain").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text()?.trim()?.toTitleCase() ?: return null
        val href = fixUrlNull(this.selectFirst("h3 a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src")?.trim())

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm" // ! Might change in the future

        val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
        val cseLibVersion = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)

        val response =
            app.get("https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${query}&safe=off&cse_tok=${cseToken}&oq=${query}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F")
        Log.d("BLX", "Search result: ${response.text}")

        val titles = Regex(""""titleNoFormatting": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val urls = Regex(""""url": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val posterUrls = Regex(""""ogImage": "(.*)"""").findAll(response.text)
            .map { it.groupValues[1].trim() }
            .toList()

        val searchResponses = mutableListOf<TvSeriesSearchResponse>()

        for (i in titles.indices) {
            val title = titles[i].split("İzle")[0].trim().toTitleCase()
            val url = urls.getOrNull(i) ?: continue
            val posterUrl = posterUrls.getOrNull(i) ?: continue

            if (!url.contains("belgeseldizi")) continue
            searchResponses.add(newTvSeriesSearchResponse(title, url, TvType.Documentary) {
                this.posterUrl = posterUrl
            })
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2.gen-title")?.text()?.trim()?.toTitleCase() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.gen-tv-show-top img")?.attr("src")?.trim()) ?: return null
        val description = document.selectFirst("div.gen-single-tv-show-info p")?.text()?.trim()
        val tags = document.select("div.gen-socail-share a[href*='belgeselkanali']")
            .map { it.attr("href").split("/").last().replace("-", " ").toTitleCase() }

        var counter = 0
        val episodes = document.select("div.gen-movie-contain").mapNotNull {
            val epName = it.selectFirst("div.gen-movie-info h3 a")?.text()?.trim() ?: return@mapNotNull null
            val epHref = fixUrlNull(it.selectFirst("div.gen-movie-info h3 a")?.attr("href")) ?: return@mapNotNull null

            val seasonName = it.selectFirst("div.gen-single-meta-holder ul li")?.text()?.trim() ?: ""
            var epEpisode = Regex("""Bölüm (\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val epSeason = Regex("""Sezon (\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (epEpisode == 0) {
                epEpisode = counter++
            }

            newEpisode(epHref) {
                this.name = epName
                this.season = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("BLX", "loadLinks data » $data")

        val sourceHtml = app.get(data).body?.string() ?: return false

        // 1. Bölüm ID'sini bul (id="no16357")
        val idMatch = Regex("""id="no(\d+)"""").find(sourceHtml)
        val bolumId = idMatch?.groupValues?.get(1) ?: return false
        Log.d("BLX", "Bölüm ID » $bolumId")

        // 2. diziGetir() fonksiyonundan kaynak kodlarını (ic1, ic2, ic3) al
        // onclick="diziGetir('16357','5','4','0','Barborassa',...);"
        val diziGetirMatch = Regex("""diziGetir\('$bolumId',\s*'(\d+)',\s*'(\d+)',\s*'(\d+)'""").find(sourceHtml)
        val ic1 = diziGetirMatch?.groupValues?.get(1) ?: ""
        val ic2 = diziGetirMatch?.groupValues?.get(2) ?: ""
        val ic3 = diziGetirMatch?.groupValues?.get(3) ?: ""

        Log.d("BLX", "Kaynak kodları » ic1=$ic1, ic2=$ic2, ic3=$ic3")

        // 3. Kaynak kodlarını endpoint'lere çevir
        val sourceCodes = listOf(ic1, ic2, ic3).filter { it.isNotEmpty() }

        if (sourceCodes.isNotEmpty()) {
            sourceCodes.forEachIndexed { index, ic ->
                val endpoint = sourceMap[ic]
                if (endpoint != null) {
                    val url = "$mainUrl/video/data/${endpoint}.php?id=$bolumId&sira=${index + 1}"
                    Log.d("BLX", "Kaynak ${index + 1} (ic=$ic) » $url")
                    tryExtractFromUrl(url, data, callback)
                }
            }
        } else {
            // Fallback: tüm endpoint'leri dene
            Log.d("BLX", "Kaynak kodu bulunamadı, tüm endpoint'ler deneniyor...")
            val allEndpoints = listOf("new1", "new2", "new3", "new4", "new5")
            allEndpoints.forEachIndexed { index, ep ->
                val url = "$mainUrl/video/data/${ep}.php?id=$bolumId&sira=${index + 1}"
                tryExtractFromUrl(url, data, callback)
            }
        }

        return true
    }

    private suspend fun tryExtractFromUrl(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = withTimeout(5_000) {
                app.get(url, headers = mapOf("Referer" to referer))
            }
            val html = response.text
            Log.d("BLX", "Response from $url (${html.length} chars)")

            // Pattern 1: file:"...", label:"..." (JSON benzeri)
            val fileLabelPattern = Regex(
                """file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
            val matches = fileLabelPattern.findAll(html).toList()

            if (matches.isNotEmpty()) {
                matches.forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    val label = match.groupValues[2].trim()
                    Log.d("BLX", "Bulundu » $label : $videoUrl")

                    val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                    val quality = when {
                        label.contains("1080", true) || label.contains("FULL", true) -> Qualities.P1080.value
                        label.contains("720", true)  || label.contains("HD", true)   -> Qualities.P720.value
                        label.contains("480", true)  || label.contains("SD", true)   -> Qualities.P480.value
                        label.contains("360", true)  -> Qualities.P360.value
                        label.contains("240", true)  -> Qualities.P240.value
                        else -> Qualities.Unknown.value
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - $label",
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                }
                return
            }

            // Pattern 2: file:"..." (label olmadan)
            val fileOnlyPattern = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val fileOnlyMatches = fileOnlyPattern.findAll(html).toList()

            if (fileOnlyMatches.isNotEmpty()) {
                fileOnlyMatches.forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                    Log.d("BLX", "Bulundu (label'sız) » $finalUrl")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer
                        }
                    )
                }
                return
            }

            // Pattern 3: iframe src
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeMatches = iframePattern.findAll(html).toList()

            if (iframeMatches.isNotEmpty()) {
                iframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1].trim()
                    val finalIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                    Log.d("BLX", "Iframe bulundu » $finalIframeUrl")

                    when {
                        finalIframeUrl.contains("ok.ru") || finalIframeUrl.contains("odnoklassniki") -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Odnoklassniki",
                                    name = "Odnoklassniki",
                                    url = finalIframeUrl,
                                    type = INFER_TYPE
                                ) { this.referer = referer }
                            )
                        }
                        finalIframeUrl.contains("drive.google.com") -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "GoogleDrive",
                                    name = "Google Drive",
                                    url = finalIframeUrl.replace("/view", "/preview"),
                                    type = INFER_TYPE
                                ) { this.referer = referer }
                            )
                        }
                        else -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} - iframe",
                                    url = finalIframeUrl,
                                    type = INFER_TYPE
                                ) { this.referer = referer }
                            )
                        }
                    }
                }
                return
            }

            // Pattern 4: Direkt video URL'leri
            val directVideoPattern = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            directVideoPattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1].trim()
                Log.d("BLX", "Direkt video » $videoUrl")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = referer }
                )
            }

        } catch (e: Exception) {
            Log.e("BLX", "Hata ($url): ${e.message}")
        }
    }
}
