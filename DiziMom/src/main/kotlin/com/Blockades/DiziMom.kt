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
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val pageUrl = if (page == 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }
        Log.d(name, "Sayfa URL: $pageUrl")

        val document = app.get(pageUrl).document

        // Hem dizi kartı (single-item) hem bölüm kartı (list-episodes) desteklenir
        val elements = document.select("div.single-item, div.list-episodes")
        val home = elements.mapNotNull { it.toSearchResult() }

        // Sayfalama: div.sayfalama içinde sonraki sayfa linki var mı?
        val hasNext = document.select("div.sayfalama a[href*='/page/']")
            .any { it.text().trim() != "Son »" }

        Log.d(name, "getMainPage - ${home.size} icerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // 1) Dizi kartı: div.single-item
        var titleElement = this.selectFirst("div.categorytitle a")
        var title = titleElement?.text()?.trim()
        var href = titleElement?.attr("href")
        var poster = this.selectFirst("div.cat-img img")?.attr("data-src")
            ?: this.selectFirst("div.cat-img img")?.attr("src")
        var imdbText = this.selectFirst("div.imdbp")?.text()?.trim()
        var year = this.select("div.dizimeta:contains(Yapım Yılı)").firstOrNull()
            ?.parent()?.ownText()?.trim()?.toIntOrNull()

        // 2) Bölüm kartı: div.list-episodes
        if (titleElement == null) {
            titleElement = this.selectFirst("div.episode-name a")
            title = titleElement?.text()?.trim()
            href = titleElement?.attr("href")
            // Poster: div.poster div.img img (dil ikonuyla çakışmasın)
            poster = this.selectFirst("div.poster div.img img")?.attr("data-src")
                ?: this.selectFirst("div.poster div.img img")?.attr("src")
            // Yıl: bölüm kartlarında yok, ama başlıktan yakalanabilir
            year = title?.let {
                Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
            }
            imdbText = null
        }

        if (href.isNullOrBlank() || title.isNullOrBlank()) return null

        val fixedHref = fixUrlNull(href) ?: return null
        val fixedPoster = fixUrlNull(poster)

        val imdbScore = imdbText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)?.toFloatOrNull()
        }

        // Bölüm kartları dizi olabilir; ama tek tek bölüm linkleri film gibi davranır
        val type = if (this.hasClass("single-item")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, fixedHref, type) {
            this.posterUrl = fixedPoster
            this.year = year
            if (imdbScore != null) this.score = Score.from10(imdbScore)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search basladi - Sorgu: $query")
        return try {
            // Nonce'u ana sayfadan çek (live-search-js-extra script'i base64 gömülü)
            val homeDoc = app.get(mainUrl).document
            val scriptTag = homeDoc.selectFirst("script#live-search-js-extra")
            val base64Src = scriptTag?.attr("src")
            val base64Part = base64Src?.substringAfter("base64,", "")

            val nonce = if (!base64Part.isNullOrBlank()) {
                try {
                    val decoded = String(
                        android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                    // JSON: {"ajax_url":"...","admin_ajax_nonce":"0721b9225c"}
                    Regex(""""admin_ajax_nonce"\s*:\s*"([^"]+)"""")
                        .find(decoded)
                        ?.groupValues
                        ?.get(1)
                } catch (e: Exception) {
                    Log.e(name, "Nonce base64 decode hatası: ${e.message}")
                    null
                }
            } else null

            if (nonce.isNullOrBlank()) {
                Log.e(name, "Arama nonce değeri bulunamadı")
                return emptyList()
            }
            Log.d(name, "Nonce: $nonce")

            val postUrl = "$mainUrl/wp-admin/admin-ajax.php"
            // NOT: Bu action/parametre isimleri doğrulanmalı.
            // live-search JS dosyası incelenip kesinleştirilmeli.
            val body = "action=live_search&s=${query}&_wpnonce=$nonce"
            val response = app.post(
                url = postUrl,
                headers = mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "accept" to "*/*"
                ),
                requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )

            val doc = Jsoup.parse(response.text)
            val elements = doc.select("div.searchelement, div.search-result, li.search-item")
            Log.d(name, "Arama sonucu eleman sayısı: ${elements.size}")

            val results = elements.mapNotNull { element ->
                val titleLink = element.select("a[href]").firstOrNull {
                    it.parent()?.`is`("div.search-cat-img") != true && it.text().isNotBlank()
                } ?: return@mapNotNull null

                val href = fixUrlNull(titleLink.attr("href")) ?: return@mapNotNull null
                val title = titleLink.text().trim()
                val poster = fixUrlNull(
                    element.selectFirst("div.search-cat-img img")?.attr("src")
                        ?: element.selectFirst("img")?.attr("src")
                )
                val year = element.selectFirst("#search-cat-year")?.text()?.trim()?.toIntOrNull()

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }

            Log.d(name, "search tamamlandi - ${results.size} sonuc bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatasi: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch basladi - Sorgu: $query")
        return search(query)
    }

    // ===================== LOAD =====================
    // NOT: Detay sayfası HTML'i elimde olmadığı için buradaki selectorler
    // değiştirilmedi. Detay HTML gelince ayrıca düzeltilecek.
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load basladi - URL: $url")

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        ).document

        // ===== BASLIK =====
        val title = document.selectFirst("h1.title-border")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (title == null) {
            Log.e(name, "load - Baslik bulunamadi")
            return null
        }

        // ===== POSTER =====
        val poster = fixUrlNull(
            document.selectFirst("div.info_move .image img")?.attr("data-src")
                ?: document.selectFirst("div.info_move .image img")?.attr("src")
                ?: document.selectFirst("div.category_image img")?.attr("data-src")
                ?: document.selectFirst("div.category_image img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val isSeries = document.selectFirst("div.bolumust") != null ||
                document.selectFirst("#myBtnContainer") != null

        if (isSeries) {
            Log.d(name, "load - Dizi sayfasi algılandı")

            val year = document.select("div.dizimeta:contains(Yapım Yılı)").firstOrNull()
                ?.parent()?.ownText()?.trim()?.toIntOrNull()

            val description = document.selectFirst("div.category_desc")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            val tags = document.select("div.genres a").map { it.text().trim() }.distinct()

            val imdbText = document.select("div.dizimeta:contains(IMDB)").firstOrNull()
                ?.parent()?.ownText()?.trim()
            val rating = imdbText?.let {
                Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
            }

            val actorText = document.select("div.dizimeta:contains(Oyuncular)").firstOrNull()
                ?.parent()?.ownText()?.trim()
            val actors: List<Pair<Actor, String?>> = actorText?.split(",")?.mapNotNull { name ->
                val cleanName = name.trim()
                if (cleanName.isNotEmpty()) Pair<Actor, String?>(Actor(cleanName, null), null) else null
            } ?: emptyList()

            val trailerRaw = document.selectFirst("#trailer .trailer-video")?.attr("data-src")
                ?: document.selectFirst("#trailer .trailer-video")?.attr("src")
                ?: ""
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
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epText
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }

            val sortedEpisodes = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) this.score = Score.from10(rating.toFloat())
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            Log.d(name, "load - Film sayfasi algılandı")

            val year = document.select("div.info_content .detail .center span")
                .firstOrNull { it.selectFirst("small")?.text()?.contains("Çıkış Yılı") == true }
                ?.text()
                ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

            val description = document.selectFirst("div.desc.yeniscroll")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            val tags = document.select("div.info_content .detail .center a[rel='category tag']")
                .map { it.text().trim() }
                .distinct()

            val imdbText = document.select("div.info_content .detail .center span")
                .firstOrNull { it.selectFirst("small")?.text()?.contains("IMDb") == true }
                ?.text()
            val rating = imdbText?.let {
                Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
            }

            val duration = document.select("div.info_content .detail .center span")
                .firstOrNull { it.selectFirst("small")?.text()?.contains("Film Süre") == true }
                ?.text()
                ?.let { Regex("""(\d+)\s*Dakika""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val actorText = document.select("div.info_content .detail .center span")
                .firstOrNull { it.selectFirst("small")?.text()?.contains("Oyuncular") == true }
                ?.text()
            val actors: List<Pair<Actor, String?>> = actorText
                ?.substringAfter("Oyuncular", "")
                ?.split(",")
                ?.mapNotNull { name ->
                    val cleanName = name.trim()
                    if (cleanName.isNotEmpty()) Pair<Actor, String?>(Actor(cleanName, null), null) else null
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
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks basladi - Data: $data")

        val document = app.get(data).document

        var embedUrl = document.selectFirst("iframe")?.attr("data-src")
            ?: document.selectFirst("iframe")?.attr("src")

        if (embedUrl.isNullOrBlank()) {
            val videoJson = document.selectFirst("script[type='application/ld+json']")?.data()
            if (videoJson != null) {
                val embedMatch = Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(videoJson)
                embedUrl = embedMatch?.groupValues?.get(1)
            }
        }

        if (embedUrl.isNullOrBlank()) {
            Log.e(name, "loadLinks - Embed URL bulunamadı")
            return false
        }

        if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"
        Log.d(name, "loadLinks - Embed URL: $embedUrl")

        loadExtractor(embedUrl, data, subtitleCallback, callback)
        return true
    }
}
