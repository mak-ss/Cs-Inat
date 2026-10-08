// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır. (ATV için uyarlanmıştır)

package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class Atv : MainAPI() {
    override var mainUrl              = "https://www.atv.com.tr"
    override var name                 = "ATV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "eski-diziler", "a2tv", "arama",
        "kunye", "iletisim", "bize-ulasin", "gizlilik-bildirimi",
        "veri-politikasi", "uydu-frekanslari", "site-haritasi",
        "rss-bilgi", "adblock", "retro-d", "filmler",
        "milyoner", "webtv", "diger", "kadro", "fragmanlar",
        "ozetler", "ozel-klipler", "foto-galeri", "oyuncular",
        "hikaye-ve-kunye", "d-shorts", "bolumler"
    )

    private val trailerKeywords = listOf(
        "fragman", "tanitim", "tanıtım", "onizleme", "önizleme",
        "teaser", "trailer", "ozet", "özet", "promo", "kamera-arkasi"
    )

    private val excludedEpisodeNumbers = setOf(
        1392, 2384, 2390, 3817, 10333
    )

    private val monthNames = listOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
    )

    private val monthNumbers = mapOf(
        "ocak" to 1, "şubat" to 2, "subat" to 2, "mart" to 3, "nisan" to 4,
        "mayıs" to 5, "mayis" to 5, "haziran" to 6, "temmuz" to 7,
        "ağustos" to 8, "agustos" to 8, "eylül" to 9, "eylul" to 9,
        "ekim" to 10, "kasım" to 11, "kasim" to 11, "aralık" to 12, "aralik" to 12
    )

    private val dayNames = listOf(
        "Pazar", "Pazartesi", "Salı", "Çarşamba",
        "Perşembe", "Cuma", "Cumartesi"
    )

    private val dateRegex = Regex(
        "(\\d{1,2})\\s+(Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık)\\s+(\\d{4})",
        RegexOption.IGNORE_CASE
    )

    private val cardSelectors = listOf(
        "div.diziler-list div.card",
        "div.series-list div.card",
        "ul.dizi-list li",
        "div[class*=dizi] a[href]",
        "div[class*=series] a[href]",
        "div.card a[href]",
        "li.series-item a[href]",
        "article.card a[href]",
        "figure a[href]"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"      to "Diziler",
        "${mainUrl}/eski-diziler" to "Eski Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenPaths = mutableSetOf<String>()

        try {
            val listDoc = app.get(request.data).document

            var cards: List<Element> = emptyList()
            for (selector in cardSelectors) {
                val found = listDoc.select(selector)
                if (found.size >= 3) {
                    cards = found
                    Log.d("ATV", "Kart seçici tuttu: $selector (${found.size} kart)")
                    break
                }
            }

            if (cards.isEmpty()) {
                Log.w("ATV", "Hiçbir kart seçicisi tutmadı, tüm a[href] deneniyor")
                cards = listDoc.select("a[href]")
            }

            cards.forEach { element ->
                val result = if (element.tagName() == "a") {
                    element.toListPageResult()
                } else {
                    element.toCardResult()
                }
                if (result != null) {
                    val key = normalizeForCompare(result.url)
                    if (key != null && seenPaths.add(key)) {
                        results.add(result)
                    }
                }
            }

            Log.d("ATV", "Liste sayfasından ${results.size} öğe alındı (${request.name})")
        } catch (e: Exception) {
            Log.e("ATV", "Liste sayfası hatası: ${e.message}")
        }

        try {
            val mainDoc = app.get(mainUrl).document
            val menuSelector = when (request.name) {
                "Diziler"      -> "div.series-drop .sub-menu-list li a[href]"
                "Eski Diziler" -> "div.series-drop .sub-menu-list li a[href]"
                else -> ""
            }
            if (menuSelector.isNotEmpty()) {
                mainDoc.select(menuSelector).forEach { element ->
                    val result = element.toMenuItemResult()
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            results.add(result)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ATV", "Menü çekme hatası: ${e.message}")
        }

        Log.d("ATV", "getMainPage: ${request.name} -> ${results.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, results))
        )
    }

    private fun normalizeForCompare(url: String): String? {
        return try {
            var p = url.lowercase(Locale.getDefault())
            p = p.replace("https://www.atv.com.tr/", "")
            p = p.replace("https://atv.com.tr/", "")
            p = p.replace("http://www.atv.com.tr/", "")
            p = p.replace("http://atv.com.tr/", "")
            p = p.removePrefix("/")
            p = p.substringBefore("?").substringBefore("#")
            p = p.trimEnd('/')
            if (p.isEmpty()) null else p
        } catch (e: Exception) {
            null
        }
    }

    private fun isTrailer(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        return trailerKeywords.any { lower.contains(it) }
    }

    private fun Element.extractPoster(): String? {
        val img = this.selectFirst("img")
        if (img != null) {
            val attrs = listOf(
                "data-src", "data-original", "data-lazy-src", "data-lazy",
                "src", "data-srcset", "srcset"
            )
            for (attr in attrs) {
                val value = img.attr(attr).trim()
                if (value.isNotEmpty() && !value.startsWith("data:")) {
                    val url = if (attr.contains("srcset")) {
                        value.substringBefore(",").trim().substringBefore(" ")
                    } else value
                    val fixed = fixUrlNull(url)
                    if (!fixed.isNullOrBlank()) return fixed
                }
            }
        }

        val source = this.selectFirst("picture source[srcset], picture source[data-srcset], source[srcset], source[data-srcset]")
        if (source != null) {
            val srcset = source.attr("srcset").ifEmpty { source.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                val url = srcset.substringBefore(",").trim().substringBefore(" ")
                fixUrlNull(url)?.let { if (it.isNotBlank()) return it }
            }
        }

        val styledDiv = this.selectFirst("[style*=background-image]")
            ?: this.parent()?.selectFirst("[style*=background-image]")
        if (styledDiv != null) {
            val style = styledDiv.attr("style")
            val match = Regex("background-image\\s*:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)", RegexOption.IGNORE_CASE)
                .find(style)
            if (match != null) {
                fixUrlNull(match.groupValues[1])?.let { if (it.isNotBlank()) return it }
            }
        }

        val dataAttrs = listOf("data-poster", "data-image", "data-thumb", "data-cover", "data-background")
        for (attr in dataAttrs) {
            val value = this.attr(attr).trim()
            if (value.isNotEmpty()) {
                fixUrlNull(value)?.let { if (it.isNotBlank()) return it }
            }
        }

        val parent = this.parent()
        if (parent != null) {
            for (attr in dataAttrs) {
                val value = parent.attr(attr).trim()
                if (value.isNotEmpty()) {
                    fixUrlNull(value)?.let { if (it.isNotBlank()) return it }
                }
            }
        }

        return null
    }

    private fun getProgramPosterFallback(path: String): String? {
        return try {
            "https://www.google.com/s2/favicons?domain=www.atv.com.tr&sz=256"
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toCardResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val hrefRaw = link.attr("href")
        if (hrefRaw.isBlank()) return null
        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null
        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        val title = this.selectFirst("h3, h4, .title, .card-title, figcaption, span.name")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        var poster = this.extractPoster()
        if (poster.isNullOrBlank()) poster = getProgramPosterFallback(path)

        Log.d("ATV", "  ✓ [$path] → $title | poster: ${poster ?: "YOK"}")

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toMenuItemResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null
        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null
        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        val title = this.text().trim().takeIf { it.isNotEmpty() } ?: return null
        if (isTrailer(title)) return null

        var poster = this.parent()?.extractPoster() ?: this.extractPoster()
        if (poster.isNullOrBlank()) poster = getProgramPosterFallback(path)

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toListPageResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null
        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null
        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        val title = this.selectFirst("figcaption p, figcaption .title, h2, h3, .title, .caption")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        var poster = this.extractPoster() ?: this.parent()?.extractPoster()
        if (poster.isNullOrBlank()) poster = getProgramPosterFallback(path)

        Log.d("ATV", "  ✓ [$path] → $title | poster: ${poster ?: "YOK"}")

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun normalizePath(url: String): String? {
        var path = url
        path = path.replace("https://www.atv.com.tr", "")
        path = path.replace("https://atv.com.tr", "")
        path = path.replace("http://www.atv.com.tr", "")
        path = path.replace("http://atv.com.tr", "")
        path = path.substringBefore("?").substringBefore("#")
        path = path.trim('/')

        if (path.isEmpty()) return null
        if (path.contains(".")) return null
        if (path.length < 2) return null

        return path
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()
        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            return allContentCache
        }

        val allContent = mutableListOf<SearchResponse>()
        val seenPaths = mutableSetOf<String>()

        val pagesToScan = listOf(
            "${mainUrl}/diziler",
            "${mainUrl}/eski-diziler"
        )

        for (pageUrl in pagesToScan) {
            try {
                val document = app.get(pageUrl).document

                var cards: List<Element> = emptyList()
                for (selector in cardSelectors) {
                    val found = document.select(selector)
                    if (found.size >= 3) {
                        cards = found
                        break
                    }
                }
                if (cards.isEmpty()) cards = document.select("a[href]")

                cards.forEach { element ->
                    val result = if (element.tagName() == "a") {
                        element.toListPageResult()
                    } else {
                        element.toCardResult()
                    }
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            allContent.add(result)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ATV", "Sayfa çekme hatası ($pageUrl): ${e.message}")
            }
        }

        try {
            val mainDoc = app.get(mainUrl).document
            mainDoc.select("div.series-drop .sub-menu-list li a[href]")
                .forEach { element ->
                    val result = element.toMenuItemResult()
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            allContent.add(result)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("ATV", "Menü çekme hatası: ${e.message}")
        }

        allContentCache = allContent
        cacheTime = currentTime
        Log.d("ATV", "getAllContent: ${allContent.size} öğe önbelleğe alındı")
        return allContent
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val allContent = getAllContent()
        val searchQuery = query.lowercase(Locale.getDefault())
        return allContent.filter {
            it.name.lowercase(Locale.getDefault()).contains(searchQuery)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (isTrailer(url)) {
            Log.d("ATV", "Fragman sayfası atlandı: $url")
            return null
        }

        val document = app.get(url).document

        if (url.contains("/izle")) {
            if (isTrailer(url)) return null

            val title = document.selectFirst("h1.video-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null

            if (isTrailer(title)) return null

            var poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
                ?: fixUrlNull(document.selectFirst("meta[name=twitter:image]")?.attr("content"))

            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val epNum = extractEpisodeNumber(url)

            if (epNum != null && excludedEpisodeNumbers.contains(epNum)) {
                Log.d("ATV", "Hariç tutulan bölüm numarası: $epNum")
                return null
            }

            val episode = newEpisode(url) {
                this.name = title
                this.episode = epNum
                this.posterUrl = poster
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        var poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("meta[name=twitter:image]")?.attr("content"))

        if (poster.isNullOrBlank()) {
            poster = document.selectFirst(
                "img[src*='poster'], img[src*='cover'], img[src*='jacket'], " +
                "img[data-src*='poster'], img[data-src*='cover'], " +
                ".program-poster img, .show-poster img, .cover-image img, " +
                ".detail-poster img, .dizi-poster img, .poster img"
            )?.let { img ->
                fixUrlNull(img.attr("data-src").ifEmpty {
                    img.attr("src").ifEmpty { img.attr("data-original") }
                })
            }
        }

        if (poster.isNullOrBlank()) poster = document.body().extractPoster()
        if (poster.isNullOrBlank()) poster = getProgramPosterFallback(url)

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getAllEpisodes(document, url)

        Log.d("ATV", "load: $title -> ${episodes.size} bölüm | poster: ${poster ?: "YOK"}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun extractEpisodeNumber(url: String): Int? {
        val lowerUrl = url.lowercase(Locale.getDefault())

        Regex("(\\d+)-bolum(?:[/\\-]|$)", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                it.groupValues[1].toIntOrNull()?.let { n -> if (n > 0) return n }
            }

        Regex("bolum-(\\d+)(?:[/\\-]|$)", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                it.groupValues[1].toIntOrNull()?.let { n -> if (n > 0) return n }
            }

        Regex("/(\\d+)/izle", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                it.groupValues[1].toIntOrNull()?.let { n -> if (n > 0) return n }
            }

        return null
    }

    private suspend fun getAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        val cleanBaseUrl = baseUrl.substringBefore("?").substringBefore("#").trimEnd('/')
        val slug = cleanBaseUrl.substringAfter(mainUrl).trim('/').substringBefore("/")

        val allEpisodesUrls = mutableListOf<String>()

        document.select("a[href*='/bolumler']").forEach { el ->
            fixUrlNull(el.attr("href"))?.let { allEpisodesUrls.add(it) }
        }
        document.select("a:contains(TÜMÜ), a:contains(Tümü), a:contains(Tüm Bölümler)").forEach { el ->
            fixUrlNull(el.attr("href"))?.let { allEpisodesUrls.add(it) }
        }

        allEpisodesUrls.add("$cleanBaseUrl/bolumler")
        allEpisodesUrls.add("$mainUrl/ajax/series/$slug/episodes")
        allEpisodesUrls.add("$mainUrl/ajax/$slug/episodes")

        for (url in allEpisodesUrls.distinct()) {
            try {
                Log.d("ATV", "Bölüm listesi deneniyor: $url")
                val doc = app.get(
                    url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to baseUrl
                    )
                ).document

                val found = extractEpisodesFromDoc(doc)
                if (found.isNotEmpty()) {
                    Log.d("ATV", "✓ $url -> ${found.size} bölüm bulundu")
                    allEpisodes.addAll(found)
                } else {
                    Log.d("ATV", "✗ $url -> bölüm yok")
                }
            } catch (e: Exception) {
                Log.d("ATV", "Deneme başarısız ($url): ${e.message}")
            }
        }

        val staticFromDetail = extractEpisodesFromDoc(document)
        Log.d("ATV", "Detay sayfasından doğrudan ${staticFromDetail.size} bölüm")
        allEpisodes.addAll(staticFromDetail)

        try {
            val sezonLinks = document.select("a[href*='sezon'], a[href*='sezonlar'], a[href*='season']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .distinct()

            for (sezonUrl in sezonLinks) {
                try {
                    val doc = app.get(sezonUrl).document
                    val found = extractEpisodesFromDoc(doc)
                    if (found.isNotEmpty()) {
                        Log.d("ATV", "Sezon sayfası: $sezonUrl -> ${found.size} bölüm")
                        allEpisodes.addAll(found)
                    }
                } catch (e: Exception) {
                    Log.d("ATV", "Sezon hatası ($sezonUrl): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ATV", "Sezon tarama hatası: ${e.message}")
        }

        val uniqueByUrl = allEpisodes.distinctBy { it.data }

        val filtered = uniqueByUrl.filter { ep ->
            val num = ep.episode
            num == null || !excludedEpisodeNumbers.contains(num)
        }

        val withNumber = filtered.filter { it.episode != null && it.episode!! > 0 }
        val withoutNumber = filtered.filter { it.episode == null || it.episode == 0 }

        val sortedWithNumber = withNumber.sortedBy { it.episode }
        val sortedWithoutNumber = withoutNumber.sortedBy { extractEpisodeNumber(it.data) ?: Int.MAX_VALUE }

        val combined = sortedWithNumber + sortedWithoutNumber

        val result = fillMissingDates(combined)

        Log.d("ATV", "Toplam ${result.size} benzersiz bölüm")
        result.take(15).forEach { ep ->
            Log.d("ATV", "  ${ep.episode}. Bölüm -> ${ep.name}")
        }

        return result
    }

    /**
     * ★ Eksik tarihleri komşu bölümlerden tahmin ederek doldurur.
     */
    private fun fillMissingDates(episodes: List<Episode>): List<Episode> {
        if (episodes.isEmpty()) return episodes

        fun parseDate(text: String): Calendar? {
            val m = dateRegex.find(text) ?: return null
            val day = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
            val monthNameRaw = m.groupValues.getOrNull(2) ?: return null
            val monthName = monthNameRaw.lowercase(Locale.getDefault())
            val month = monthNumbers[monthName] ?: return null
            val year = m.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
            return Calendar.getInstance().apply {
                set(year, month - 1, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        val dateMap = mutableMapOf<Int, Calendar>()
        episodes.forEach { ep ->
            val num = ep.episode ?: return@forEach
            val date = parseDate(ep.name)
            if (date != null) dateMap[num] = date
        }

        if (dateMap.isEmpty()) {
            Log.d("ATV", "Hiç tarih bulunamadı, doldurma yapılmadı")
            return episodes
        }

        Log.d("ATV", "Tarih bulunan bölümler: ${dateMap.keys.sorted()}")

        val result = episodes.map { ep ->
            val num = ep.episode ?: return@map ep
            if (dateMap.containsKey(num)) return@map ep

            val beforeKeys = dateMap.keys.filter { it < num }
            val afterKeys = dateMap.keys.filter { it > num }
            val beforeNum = beforeKeys.maxOrNull()
            val afterNum = afterKeys.minOrNull()

            val estimatedDate: Calendar? = when {
                beforeNum != null && afterNum != null -> {
                    val beforeDate = dateMap[beforeNum] ?: return@map ep
                    val afterDate = dateMap[afterNum] ?: return@map ep
                    val totalSteps = afterNum - beforeNum
                    if (totalSteps <= 0) return@map ep
                    val stepMs = (afterDate.timeInMillis - beforeDate.timeInMillis) / totalSteps
                    Calendar.getInstance().apply {
                        timeInMillis = beforeDate.timeInMillis + stepMs * (num - beforeNum)
                    }
                }
                beforeNum != null -> {
                    val beforeDate = dateMap[beforeNum] ?: return@map ep
                    Calendar.getInstance().apply {
                        timeInMillis = beforeDate.timeInMillis + 7L * 24 * 60 * 60 * 1000
                    }
                }
                afterNum != null -> {
                    val afterDate = dateMap[afterNum] ?: return@map ep
                    Calendar.getInstance().apply {
                        timeInMillis = afterDate.timeInMillis - 7L * 24 * 60 * 60 * 1000
                    }
                }
                else -> null
            }

            if (estimatedDate != null) {
                val day = estimatedDate.get(Calendar.DAY_OF_MONTH)
                val monthIndex = estimatedDate.get(Calendar.MONTH)
                val year = estimatedDate.get(Calendar.YEAR)
                val dayOfWeekIndex = estimatedDate.get(Calendar.DAY_OF_WEEK) - 1

                val mName: String = monthNames.getOrElse(monthIndex) { "Ocak" }
                val dName: String = dayNames.getOrElse(dayOfWeekIndex) { "" }

                ep.name = "$num. $day $mName $year, $dName"
                Log.d("ATV", "Tahmin edilen tarih: $num -> ${ep.name}")
            }

            ep
        }

        return result
    }

    private fun extractEpisodesFromDoc(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val allIzleLinks = document.select("a[href*='/izle']")
        Log.d("ATV", "=== extractEpisodesFromDoc: ${allIzleLinks.size} adet /izle linki ===")

        val episodeLinks = allIzleLinks
            .filter { element ->
                val href = element.attr("href")

                if (isTrailer(href)) return@filter false

                val text = element.text()
                if (isTrailer(text)) return@filter false

                val isEpisodeLink = href.contains("-bolum") ||
                                    href.contains("/bolum-") ||
                                    Regex("/\\d+/izle", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
                                    Regex("/\\d+-bolum", RegexOption.IGNORE_CASE).containsMatchIn(href)

                href.endsWith("/izle") && isEpisodeLink
            }

        episodeLinks.distinctBy { it.attr("href") }.forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            if (isTrailer(href)) return@forEach

            val rawTitle = extractBestTitle(element)

            // ★ null güvenliği
            val safeTitle: String = if (rawTitle.isNullOrBlank()) "Bölüm" else rawTitle

            if (isTrailer(safeTitle)) return@forEach

            val epNum = extractEpisodeNumber(href)

            if (epNum != null && excludedEpisodeNumbers.contains(epNum)) {
                Log.d("ATV", "Hariç tutulan bölüm: $epNum -> $href")
                return@forEach
            }

            newEpisode(href) {
                this.name = safeTitle
                this.episode = epNum
            }?.let { episodes.add(it) }
        }

        Log.d("ATV", "extractEpisodesFromDoc sonuç: ${episodes.size} bölüm")
        return episodes
    }

    /**
     * ★ En iyi başlığı seç.
     */
    private fun extractBestTitle(element: Element): String? {
        val ownText = element.text().trim()
        dateRegex.find(ownText)?.let {
            return buildTitleWithDate(element, it.value)
        }

        element.select("*").forEach { child ->
            val childText = child.text().trim()
            dateRegex.find(childText)?.let {
                return buildTitleWithDate(element, it.value)
            }
        }

        val parent = element.parent()
        if (parent != null) {
            val parentText = parent.text().trim()
            dateRegex.find(parentText)?.let {
                return buildTitleWithDate(element, it.value)
            }

            parent.select("*").forEach { sib ->
                val sibText = sib.text().trim()
                dateRegex.find(sibText)?.let {
                    return buildTitleWithDate(element, it.value)
                }
            }
        }

        val grandParent = element.parent()?.parent()
        if (grandParent != null) {
            val gpText = grandParent.text().trim()
            dateRegex.find(gpText)?.let {
                return buildTitleWithDate(element, it.value)
            }
        }

        element.selectFirst(".style-01, .style-02, h3, .title, .date, span")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        ownText.takeIf { it.isNotEmpty() }?.let { return it }

        return null
    }

    /**
     * ★ Tarihi ve bölüm numarasını birleştirip başlık oluşturur.
     */
    private fun buildTitleWithDate(element: Element, dateText: String): String {
        val ownText = element.text().trim()
        val numMatch = Regex("^(\\d+)\\s*\\.").find(ownText)

        val numFromText: Int? = numMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val hrefAttr: String = element.attr("href")
        val numFromUrl: Int? = extractEpisodeNumber(hrefAttr)
        val num: Int? = numFromText ?: numFromUrl

        val normalizedDate: String = normalizeDateText(dateText)

        return if (num != null) {
            "$num. $normalizedDate"
        } else {
            normalizedDate
        }
    }

    /**
     * ★ Tarih metnini normalize eder.
     */
    private fun normalizeDateText(dateText: String): String {
        val m = dateRegex.find(dateText) ?: return dateText
        val day = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return dateText
        val monthName: String = m.groupValues.getOrNull(2) ?: return dateText
        val year = m.groupValues.getOrNull(3)?.toIntOrNull() ?: return dateText

        val monthNum = monthNumbers[monthName.lowercase(Locale.getDefault())] ?: return dateText
        val cal = Calendar.getInstance().apply {
            set(year, monthNum - 1, day, 0, 0, 0)
        }
        val dayOfWeekIndex = cal.get(Calendar.DAY_OF_WEEK) - 1
        val dayOfWeek: String = dayNames.getOrElse(dayOfWeekIndex) { "" }

        val originalDayMatch = Regex(
            "(Pazartesi|Salı|Çarşamba|Perşembe|Cuma|Cumartesi|Pazar)",
            RegexOption.IGNORE_CASE
        ).find(dateText)
        val finalDay: String = originalDayMatch?.value ?: dayOfWeek

        return "$day $monthName $year, $finalDay"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ATV", "Video data: $data")

        if (isTrailer(data)) {
            Log.d("ATV", "Fragman linki atlandı: $data")
            return false
        }

        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
            var found = false

            val pageTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            if (isTrailer(pageTitle) || isTrailer(data)) {
                Log.d("ATV", "Fragman sayfası algılandı: $pageTitle")
                return false
            }

            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject")) continue

                val lowerContent = content.lowercase(Locale.getDefault())
                if (trailerKeywords.any { lowerContent.contains(it) }) continue

                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        val videoName = json.optString("name", "").lowercase(Locale.getDefault())

                        if (trailerKeywords.any { videoName.contains(it) }) continue

                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ATV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            if (!found) {
                val playerScripts = document.select("script").filter { script ->
                    val data = script.data().lowercase(Locale.getDefault())
                    (data.contains("player") || data.contains("video") ||
                     data.contains("hls") || data.contains("m3u8") ||
                     data.contains("contenturl")) &&
                    !trailerKeywords.any { data.contains(it) }
                }

                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\""),
                    Regex("\"file\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"source\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )

                for (script in playerScripts) {
                    val content = script.data()
                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            if (isTrailer(videoUrl)) return@let

                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                        if (found) break
                    }
                    if (found) break
                }
            }

            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    if (!isTrailer(embedUrl)) {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("ATV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
