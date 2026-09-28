package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class DiziMom : MainAPI() {
    override var mainUrl = "https://www.dizimom.help"
    override var name = "DiziMom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/film-tur/netflix-filmleri/" to "Netflix Filmleri",
        "${mainUrl}/netflix-dizileri-izle/" to "Netflix Dizileri",
        "${mainUrl}/yabanci-filmler-izle/" to "Yabancı Filmler",
        "${mainUrl}/yabanci-dizi-izle/" to "Yabancı Diziler",
        "${mainUrl}/turkce-dublaj-filmler/" to "Türkçe Dublajlı Filmler",
        "${mainUrl}/turkce-altyazili-filmler/" to "Türkçe Altyazılı Filmler",
        "${mainUrl}/turkce-dublaj-diziler-hd/" to "Dublajlı Diziler",
        "${mainUrl}/yerli-filmler/" to "Yerli Filmler",
        "${mainUrl}/yerli-dizi-izle/" to "Yerli Diziler",
        "${mainUrl}/tv-programlari-izle/" to "TV Programları",
        "${mainUrl}/tum-bolumler/" to "Tüm Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                      else request.data.trimEnd('/') + "/page/$page/"

        val document = app.get(pageUrl).document

        val elements = document.select("div.single-item, div.list-episodes")
        val home = elements.mapNotNull { it.toSearchResult() }

        val hasNext = document.select("div.sayfalama a[href*='/page/']")
            .any { it.text().trim() != "Son »" }

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Dizi kartı (div.single-item) — listede önce bunu dene
        var titleElement = this.selectFirst("div.categorytitle a")
        var title = titleElement?.text()?.trim()
        var href = titleElement?.attr("href")
        var poster = this.selectFirst("div.cat-img img")?.attr("data-src")
            ?: this.selectFirst("div.cat-img img")?.attr("src")
        var imdbText = this.selectFirst("div.imdbp")?.text()?.trim()
        var year = this.select("div.dizimeta:contains(Yapım Yılı)")
            .firstOrNull()?.parent()?.ownText()?.trim()?.toIntOrNull()

        // Bölüm kartı (div.list-episodes)
        if (titleElement == null) {
            titleElement = this.selectFirst("div.episode-name a")
            title = titleElement?.text()?.trim()
            href = titleElement?.attr("href")
            poster = this.selectFirst("div.poster div.img img")?.attr("data-src")
                ?: this.selectFirst("div.poster div.img img")?.attr("src")
            imdbText = null
            year = title?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        }

        if (href.isNullOrBlank() || title.isNullOrBlank()) return null

        val fixedHref = fixUrlNull(href) ?: return null
        val fixedPoster = fixUrlNull(poster)

        val imdbScore = imdbText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)?.toFloatOrNull()
        }

        val type = if (this.hasClass("single-item")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, fixedHref, type) {
            this.posterUrl = fixedPoster
            this.year = year
            if (imdbScore != null) this.score = Score.from10(imdbScore)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Nonce'u ana sayfadan live-search-js-extra script base64'ünden çek
            val homeDoc = app.get(mainUrl).document
            val scriptTag = homeDoc.selectFirst("script#live-search-js-extra")
            val base64Part = scriptTag?.attr("src")?.substringAfter("base64,", "")

            val nonce = if (!base64Part.isNullOrBlank()) {
                try {
                    val decoded = String(
                        android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                    Regex(""""admin_ajax_nonce"\s*:\s*"([^"]+)"""")
                        .find(decoded)?.groupValues?.get(1)
                } catch (e: Exception) { null }
            } else null

            if (nonce.isNullOrBlank()) return emptyList()

            // fetchResults() JS ile birebir aynı istek:
            // action=data_fetch, keyword=<query>, _wpnonce=<nonce>
            val body = "action=data_fetch&keyword=$query&_wpnonce=$nonce"
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "accept" to "*/*"
                ),
                requestBody = body.toRequestBody(
                    "application/x-www-form-urlencoded".toMediaType()
                )
            )

            val doc = Jsoup.parse(response.text)
            val elements = doc.select("div.searchelement")

            elements.mapNotNull { element ->
                val titleLink = element.select("a[href]").firstOrNull {
                    it.parent()?.`is`("div.search-cat-img") != true && it.text().isNotBlank()
                } ?: return@mapNotNull null

                val href = fixUrlNull(titleLink.attr("href")) ?: return@mapNotNull null
                val title = titleLink.text().trim()
                val poster = fixUrlNull(
                    element.selectFirst("div.search-cat-img img")?.attr("src")
                        ?: element.selectFirst("div.search-cat-img img")?.attr("data-src")
                )
                val year = element.selectFirst("#search-cat-year")?.text()?.trim()?.toIntOrNull()

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        } catch (e: Exception) {
            Log.e(name, "search hatasi: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        ).document

        // ===== BAŞLIK =====
        val title = document.selectFirst("h1.title-border")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        // ===== POSTER =====
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.info_move .image img")?.attr("data-src")
                ?: document.selectFirst("div.info_move .image img")?.attr("src")
                ?: document.selectFirst("div.category_image img")?.attr("data-src")
                ?: document.selectFirst("div.category_image img")?.attr("src")
        )

        // Bu sayfa bir BÖLÜM mü yoksa DİZİ ana sayfası mı?
        val isEpisodePage = document.selectFirst("div.inepisode") != null ||
                            document.selectFirst("div.otherepisodes") != null
        val isSeriesPage = document.selectFirst("div.category_image") != null ||
                           document.selectFirst("#myBtnContainer") != null

        // ================= BÖLÜM SAYFASI =================
        // (Star Trek örneği: tek bölüm + sezonun diğer bölümleri listesi)
        if (isEpisodePage && !isSeriesPage) {
            val description = document.selectFirst("div#bolumbilgi .infoelem")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            // Dizinin kendi sayfası
            val seriesUrl = fixUrlNull(
                document.selectFirst("div#benzerli a[rel='category tag']")?.attr("href")
            )

            // Aynı sezonun diğer bölümleri
            val episodes = mutableListOf<Episode>()
            document.select("div.otherepisodes").forEach { epBox ->
                val link = epBox.selectFirst("a[href]") ?: return@forEach
                val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                val epName = epBox.selectFirst("div.epidosename")?.text()?.trim()
                    ?: link.text()?.trim() ?: return@forEach
                val season = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        if (season != null) this.season = season
                        if (episode != null) this.episode = episode
                    }
                )
            }

            // Bu bölümün kendisini de listeye ekle (aynı sezon içinde)
            val currentSeason = Regex("""(\d+)\.Sezon""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val currentEpisode = Regex("""(\d+)\.Bölüm""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            if (currentSeason != null && currentEpisode != null &&
                episodes.none { it.season == currentSeason && it.episode == currentEpisode }) {
                episodes.add(
                    newEpisode(url) {
                        this.name = title
                        this.season = currentSeason
                        this.episode = currentEpisode
                    }
                )
            }

            val sorted = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = description
                if (seriesUrl != null) this.recommendations = listOf(seriesUrl)
            }
        }

        // ================= DİZİ ANA SAYFASI =================
        if (isSeriesPage) {
            val year = document.select("div.dizimeta:contains(Yapım Yılı)")
                .firstOrNull()?.parent()?.ownText()?.trim()?.toIntOrNull()
            val description = document.selectFirst("div.category_desc")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            val tags = document.select("div.genres a").map { it.text().trim() }.distinct()

            val imdbText = document.select("div.dizimeta:contains(IMDB)")
                .firstOrNull()?.parent()?.ownText()?.trim()
            val rating = imdbText?.let {
                Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
            }

            val actorText = document.select("div.dizimeta:contains(Oyuncular)")
                .firstOrNull()?.parent()?.ownText()?.trim()
            val actors: List<Pair<Actor, String?>> = actorText?.split(",")?.mapNotNull {
                val n = it.trim(); if (n.isNotEmpty()) Pair(Actor(n, null), null) else null
            } ?: emptyList()

            val trailerRaw = document.selectFirst("#trailer .trailer-video")?.attr("data-src")
                ?: document.selectFirst("#trailer .trailer-video")?.attr("src") ?: ""
            val trailer = when {
                trailerRaw.startsWith("//") -> "https:$trailerRaw"
                trailerRaw.startsWith("http") -> trailerRaw
                trailerRaw.isNotBlank() -> fixUrl(trailerRaw)
                else -> ""
            }

            val episodes = mutableListOf<Episode>()
            document.select("div.bolumust a[href]").forEach { link ->
                val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                val epText = link.selectFirst("div.baslik")?.text()?.trim()
                    ?: link.text()?.trim() ?: return@forEach
                val season = Regex("""(\d+)\.Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""(\d+)\.Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                if (season != null && episode != null) {
                    episodes.add(newEpisode(epHref) {
                        this.name = epText; this.season = season; this.episode = episode
                    })
                }
            }
            val sorted = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) this.score = Score.from10(rating.toFloat())
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }

        // ================= FİLM SAYFASI =================
        val year = document.select("div.info_content .detail .center span")
            .firstOrNull { it.selectFirst("small")?.text()?.contains("Çıkış Yılı") == true }
            ?.text()?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        val description = document.selectFirst("div.desc.yeniscroll")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.info_content .detail .center a[rel='category tag']")
            .map { it.text().trim() }.distinct()

        val imdbText = document.select("div.info_content .detail .center span")
            .firstOrNull { it.selectFirst("small")?.text()?.contains("IMDb") == true }?.text()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
        }

        val duration = document.select("div.info_content .detail .center span")
            .firstOrNull { it.selectFirst("small")?.text()?.contains("Film Süre") == true }
            ?.text()?.let { Regex("""(\d+)\s*Dakika""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val actorText = document.select("div.info_content .detail .center span")
            .firstOrNull { it.selectFirst("small")?.text()?.contains("Oyuncular") == true }?.text()
        val actors: List<Pair<Actor, String?>> = actorText
            ?.substringAfter("Oyuncular", "")?.split(",")?.mapNotNull {
                val n = it.trim(); if (n.isNotEmpty()) Pair(Actor(n, null), null) else null
            } ?: emptyList()

        val trailerRaw = document.selectFirst("div.btn.fragman_goster")?.attr("rel")
            ?: document.selectFirst("div.btn.fragman_goster")?.attr("href")
        val trailer = when {
            trailerRaw.isNullOrBlank() -> ""
            trailerRaw.startsWith("//") -> "https:$trailerRaw"
            trailerRaw.startsWith("http") -> trailerRaw
            else -> fixUrl(trailerRaw)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            if (rating != null) this.score = Score.from10(rating.toFloat())
            if (duration != null) this.duration = duration
            addActors(actors)
            if (trailer.isNotBlank()) addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1) iframe (data-src veya src)
        var embedUrl = document.selectFirst("div.video-container iframe")?.attr("data-src")
            ?: document.selectFirst("div.video-container iframe")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("data-src")
            ?: document.selectFirst("iframe")?.attr("src")

        // 2) JSON-LD'deki embedUrl
        if (embedUrl.isNullOrBlank()) {
            document.select("script[type='application/ld+json']").forEach { script ->
                if (!embedUrl.isNullOrBlank()) return@forEach
                val txt = script.data()
                if (txt.contains("\"VideoObject\"")) {
                    val m = Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(txt)
                    if (m != null) embedUrl = m.groupValues[1]
                }
            }
        }

        if (embedUrl.isNullOrBlank()) return false
        if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"

        loadExtractor(embedUrl, data, subtitleCallback, callback)
        return true
    }
}
