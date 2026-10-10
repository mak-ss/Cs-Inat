// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Base64
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
        "${mainUrl}"                                to "Yeni Filmler",
        "${mainUrl}/tur/aile-filmleri-izle"         to "Aile",
        "${mainUrl}/tur/aksiyon-filmleri-izle"      to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmleri-izle"    to "Animasyon",
        "${mainUrl}/tur/belgesel-izle"              to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu-filmleri-izle"  to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi-filmleri-izle"    to "Biyografi",
        "${mainUrl}/tur/dram-filmleri-izle"         to "Dram",
        "${mainUrl}/tur/fantastik-filmler-izle"     to "Fantastik",
        "${mainUrl}/tur/gerilim-filmleri-izle"      to "Gerilim",
        "${mainUrl}/tur/gizem-filmleri-izle"        to "Gizem",
        "${mainUrl}/tur/komedi-filmleri-izle"       to "Komedi",
        "${mainUrl}/tur/korku-filmleri-izle"        to "Korku",
        "${mainUrl}/tur/macera-filmleri-izle"       to "Macera",
        "${mainUrl}/tur/muzik-filmleri-izle"        to "Müzik",
        "${mainUrl}/tur/polisiye-filmler-izle"      to "Polisiye",
        "${mainUrl}/tur/romantik-filmler-izle"      to "Romantik",
        "${mainUrl}/tur/savas-filmleri-izle"        to "Savaş",
        "${mainUrl}/tur/spor-filmleri-izle"         to "Spor",
        "${mainUrl}/tur/suc-filmleri-izle"          to "Suç",
        "${mainUrl}/tur/tarih-filmleri-izle"        to "Tarih",
        "${mainUrl}/tur/yerli-film-izle"            to "Yerli"
    )

    // ------------------------------------------------------------------
    // MAIN PAGE
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        val home     = document
            .select("div.col-lg-3.col-6.poster-container")
            .mapNotNull { it.toMainPageResult() }

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

    // ------------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document
            .select("div.col-lg-3.col-6")
            .mapNotNull { it.toSearchResult() }
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

    // ------------------------------------------------------------------
    // LOAD
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.page-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("picture img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("picture source")?.attr("data-srcset"))
            ?: fixUrlNull(document.selectFirst("picture img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("article.text-white")?.text()?.trim()
        val year        = document.selectFirst("div.d-flex.flex-column.text-nowrap a")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("div.pb-0 a.btn-warning").map { it.text() }
        val rating      = document.selectFirst("div.d-flex.flex-column.text-nowrap strong.text-danger")?.text()?.trim()
        val duration    = document
            .selectFirst("div.table-responsive table > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(2) > div:nth-child(1) > strong")
            ?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()

        val fragmanElement = document
            .select("div.filmsayfala a")
            .firstOrNull { it.text().equals("fragman", ignoreCase = true) }
        val fragmanHref: String? = fragmanElement?.attr("href")
        Log.d("kraptor_", "fragmanHref = $fragmanHref")

        var trailer: String? = null
        if (!fragmanHref.isNullOrBlank()) {
            val fragmancek = app.get(fragmanHref).document
            trailer = fragmancek.selectFirst("iframe")?.attr("src")
            Log.d("kraptor_", "trailer = $trailer")
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
            this.duration  = duration
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

    // ------------------------------------------------------------------
    // LOAD LINKS
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_${this.name}", "data = $data")
        val document = app.get(data).document

        // 1) Sayfadaki iframe'i bul — önce src, sonra data-src
        val iframe = document
            .selectFirst("iframe")
            ?.let { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank() && src != "about:blank") src
                else el.attr("data-src").trim()
            }
            .orEmpty()

        Log.d("kraptor_${this.name}", "iframe = $iframe")
        if (iframe.isBlank()) return false

        val fixedIframe = fixUrlNull(iframe) ?: return false
        Log.d("kraptor_${this.name}", "fixedIframe = $fixedIframe")

        // 2) Host'a göre uygun extractor'ı çağır
        when {
            fixedIframe.contains("vidmoly", true) -> {
                extractVidmoly(fixedIframe, subtitleCallback, callback)
            }
            fixedIframe.contains("vmpx.online", true) ||
            fixedIframe.contains("vmeas.cloud", true) ||
            fixedIframe.contains("vmnow.online", true) ||
            fixedIframe.contains("vmshow.", true) ||
            fixedIframe.contains("vmwesa.", true) -> {
                extractVmpx(fixedIframe, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(fixedIframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }

    // ------------------------------------------------------------------
    // VMPX EXTRACTOR (vmpx / vmeas / vmnow altyapısı)
    // ------------------------------------------------------------------
    private suspend fun extractVmpx(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor_vmpx", "extractVmpx -> $iframeUrl")

        val playerDoc = try {
            app.get(
                iframeUrl,
                referer = "${mainUrl}/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36"
                )
            ).document
        } catch (e: Exception) {
            Log.e("kraptor_vmpx", "iframe alınamadı: ${e.message}")
            return
        }

        val html = playerDoc.html()
        Log.d("kraptor_vmpx", "player html length = ${html.length}")

        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        val found = mutableSetOf<String>()

        // 1) Doğrudan m3u8
        m3u8Regex.findAll(html).forEach { m ->
            found.add(m.value.replace("\\/", "/").replace("&amp;", "&"))
        }

        // 2) file:"..." / source:"..." gömülü link
        val fileRegex = Regex("""(?:file|source|src)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
        fileRegex.findAll(html).forEach { m ->
            found.add(m.groupValues[1].replace("\\/", "/").replace("&amp;", "&"))
        }

        // 3) Base64 şifreli
        val base64Regex = Regex("""(?:file|source|src)\s*[:=]\s*["']([A-Za-z0-9+/=]{40,})["']""")
        base64Regex.findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.contains(".m3u8")) {
                    found.add(decoded.replace("\\/", "/").replace("&amp;", "&"))
                }
            } catch (_: Exception) {}
        }

        if (found.isEmpty()) {
            Log.w("kraptor_vmpx", "m3u8 bulunamadı, fallback loadExtractor")
            loadExtractor(iframeUrl, "${mainUrl}/", subtitleCallback, callback)
            return
        }

        found.forEachIndexed { index, m3u8 ->
            Log.d("kraptor_vmpx", "m3u8[$index] = $m3u8")

            val quality = when {
                m3u8.contains("1080") -> Qualities.P1080.value
                m3u8.contains("720")  -> Qualities.P720.value
                m3u8.contains("480")  -> Qualities.P480.value
                m3u8.contains("360")  -> Qualities.P360.value
                else                  -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "${this.name} [${index + 1}]",
                    url    = m3u8,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Safari/537.36",
                        "Referer"    to iframeUrl
                    )
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // VIDMOLY EXTRACTOR (vidmoly.net / vidmoly.to / vidmoly.biz)
    // ------------------------------------------------------------------
    private suspend fun extractVidmoly(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor_vidmoly", "extractVidmoly -> $iframeUrl")

        val playerDoc = try {
            app.get(
                iframeUrl,
                referer = "${mainUrl}/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36"
                )
            ).document
        } catch (e: Exception) {
            Log.e("kraptor_vidmoly", "iframe alınamadı: ${e.message}")
            return
        }

        val html = playerDoc.html()
        Log.d("kraptor_vidmoly", "player html length = ${html.length}")

        val found = mutableSetOf<String>()

        // 1) jwplayer / plyr / videojs tipik sources JSON'u
        val sourcesRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+?\.m3u8[^"']*)["']""")
        sourcesRegex.findAll(html).forEach { m ->
            found.add(m.groupValues[1].replace("\\/", "/").replace("&amp;", "&"))
        }

        // 2) Genel m3u8 regex
        val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        m3u8Regex.findAll(html).forEach { m ->
            found.add(m.value.replace("\\/", "/").replace("&amp;", "&"))
        }

        // 3) Base64 ile şifrelenmiş kaynak
        val b64Regex = Regex("""["']([A-Za-z0-9+/=]{60,})["']""")
        b64Regex.findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.contains(".m3u8")) {
                    m3u8Regex.findAll(decoded).forEach { mm ->
                        found.add(mm.value.replace("\\/", "/").replace("&amp;", "&"))
                    }
                }
            } catch (_: Exception) {}
        }

        if (found.isEmpty()) {
            Log.w("kraptor_vidmoly", "m3u8 bulunamadı, fallback loadExtractor")
            loadExtractor(iframeUrl, "${mainUrl}/", subtitleCallback, callback)
            return
        }

        found.forEachIndexed { index, m3u8 ->
            Log.d("kraptor_vidmoly", "m3u8[$index] = $m3u8")

            val quality = when {
                m3u8.contains("1080") -> Qualities.P1080.value
                m3u8.contains("720")  -> Qualities.P720.value
                m3u8.contains("480")  -> Qualities.P480.value
                m3u8.contains("360")  -> Qualities.P360.value
                else                  -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "${this.name} [${index + 1}]",
                    url    = m3u8,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Safari/537.36",
                        "Referer"    to iframeUrl,
                        "Origin"     to "https://vidmoly.net"
                    )
                }
            )
        }
    }
}
