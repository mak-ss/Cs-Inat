package com.Blockades

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import kotlin.coroutines.resume

class DiziPal12 : MainAPI() {
    override var mainUrl = "https://dizipalw2.com/"
    override var name = "DiziPalw2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/trendler"      to "Trendler",
        "$mainUrl/diziler"  to "Diziler",
        "$mainUrl/filmler"       to "Filmler",
        "$mainUrl/anime"  to "Anime"
    )

    private fun cleanPoster(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            if (c.startsWith("data:")) continue
            if (c.endsWith(".svg")) continue
            if (c.contains("placeholder")) continue
            return c
        }
        return null
    }

    // ---------- ANASAYFA ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach

            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim()
                ?: article.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach

            val img = article.selectFirst("img")
            val poster = cleanPoster(img?.attr("src"), img?.attr("data-src"))

            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") -> TvType.Anime
                badgeClass.contains("badge-film")  -> TvType.Movie
                badgeClass.contains("badge-dizi")  -> TvType.TvSeries
                href.contains("/anime/")           -> TvType.Anime
                href.contains("/film/")            -> TvType.Movie
                else                               -> TvType.TvSeries
            }

            val year = article.selectFirst("span.grid-card-year")?.text()?.trim()?.toIntOrNull()

            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            items.add(resp)
        }

        val hasNext = document.selectFirst("nav.pagination button.pag-next:not(.disabled)") != null
            || document.selectFirst("nav.pagination a[rel=next]") != null
            || page < 20

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ---------- ARAMA ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim() ?: return@forEach
            val img = article.selectFirst("img")
            val poster = cleanPoster(img?.attr("src"), img?.attr("data-src"))
            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") || href.contains("/anime/") -> TvType.Anime
                badgeClass.contains("badge-film")  || href.contains("/film/")  -> TvType.Movie
                else -> TvType.TvSeries
            }
            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster }
            }
            results.add(resp)
        }

        if (results.isEmpty()) {
            document.select("a.homepage-card").forEach { el ->
                val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val img = el.selectFirst("img")
                val poster = cleanPoster(img?.attr("data-src"), img?.attr("src"))
                val type = if (href.contains("/anime/")) TvType.Anime
                           else if (href.contains("/film/")) TvType.Movie
                           else TvType.TvSeries
                results.add(newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster })
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- DETAY ----------
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/sezon/") && url.contains("/bolum")) {
            val seriesUrl = url.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?: return null

        val posterImg = document.selectFirst("div.series-poster img")
        val poster = cleanPoster(
            posterImg?.attr("src"),
            posterImg?.attr("data-src"),
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("p.series-summary")?.text()?.trim()
        val year = document.selectFirst("div.series-data-item:contains(Yapım Yılı) time")?.text()?.toIntOrNull()
            ?: document.selectFirst("time[datetime]")?.text()?.toIntOrNull()

        val tags = document.select("div.series-badges a.series-badge").map { it.text().trim() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        document.select("li.ep-item a[href*='-sezon-']").forEach { epAnchor ->
            val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epTitle = epAnchor.selectFirst("span.title")?.text()?.trim() ?: "Bölüm"
            val seasonMatch = Regex("(\\d+)-sezon").find(epHref)
            val episodeMatch = Regex("(\\d+)-bolum").find(epHref)
            val epSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            val epEpisode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.season = epSeason
                this.episode = epEpisode
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ---------- İZLEME LİNKLERİ ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPal12", "loadLinks data=$data")
        return try {
            // 1) Sayfayı normal şekilde çek ve doğrudan link var mı bak.
            val document = app.get(data).document
            val html = document.html()

            // Doğrudan m3u8
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value
            if (directM3u8 != null) {
                Log.d("DiziPal12", "Found direct m3u8: $directM3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "DiziPal12 HLS",
                        url = directM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Sayfada doğrudan iframe src'si varsa (örneğin mntplayer, vod.)
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='vod.']")?.attr("src")
                ?: document.selectFirst("iframe[src]")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                Log.d("DiziPal12", "Found iframeSrc: $iframeSrc")
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // 2) Şifreli data-rm-k varsa, WebView kullanarak JS'in çözmesini bekle.
            if (document.selectFirst("div[data-rm-k]") != null) {
                Log.d("DiziPal12", "data-rm-k found, using WebView to extract iframe url.")
                val iframeUrl = getIframeUrlWithWebView(data)
                if (iframeUrl != null) {
                    Log.d("DiziPal12", "WebView extracted iframe URL: $iframeUrl")
                    MntPlayer12().getUrl(fixUrl(iframeUrl), mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            // 3) Eski yöntem: episodesData içindeki embed token (yedek)
            val episodesDataMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
            if (episodesDataMatch != null) {
                val tokenMatch = Regex("""\\/embed\\/\?token=([^"\\]+)""").find(episodesDataMatch)
                    ?: Regex("""/embed/\?token=([^"\\]+)""").find(episodesDataMatch)
                if (tokenMatch != null) {
                    val embedUrl = "$mainUrl/embed/?token=${tokenMatch.groupValues[1]}"
                    Log.d("DiziPal12", "Found embed token: $embedUrl")
                    MntPlayer12().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            Log.e("DiziPal12", "Oynatıcı bulunamadı: $data")
            false
        } catch (e: Exception) {
            Log.e("DiziPal12", "loadLinks HATA » ${e.message}", e)
            false
        }
    }

    /**
     * WebView kullanarak sayfayı yükler ve JavaScript'in şifreli veriyi çözüp
     * iframe'in src'sini ayarlamasını bekler.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getIframeUrlWithWebView(url: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(appContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = USER_AGENT

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Sayfa yüklendikten sonra iframe'in src'sini kontrol et.
                // Biraz gecikme ekleyelim, çünkü JS'nin çalışması zaman alabilir.
                view?.postDelayed({
                    view.evaluateJavascript(
                        "(function() { var iframe = document.querySelector('iframe'); return iframe ? iframe.src : null; })();"
                    ) { value ->
                        val iframeUrl = value?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "null" }
                        if (iframeUrl != null) {
                            cont.resume(iframeUrl)
                        } else {
                            // Eğer iframe hala boşsa, biraz daha bekle.
                            view.postDelayed({
                                view.evaluateJavascript(
                                    "(function() { var iframe = document.querySelector('iframe'); return iframe ? iframe.src : null; })();"
                                ) { value2 ->
                                    val iframeUrl2 = value2?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "null" }
                                    cont.resume(iframeUrl2)
                                }
                            }, 2000) // 2 saniye daha bekle
                        }
                    }
                }, 3000) // İlk bekleme 3 saniye
            }
        }

        webView.loadUrl(url)

        cont.invokeOnCancellation {
            webView.destroy()
        }
    }
}
