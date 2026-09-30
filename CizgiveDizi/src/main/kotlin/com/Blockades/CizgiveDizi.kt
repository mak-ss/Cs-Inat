// ! Bu araç @Blockades tarafından yazılmıştır.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import java.net.URLEncoder
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.newEpisode

class CizgiveDizi : MainAPI() {
    override var mainUrl = "https://cizgivedizi.com"
    override var name = "CizgiveDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Cartoon)

    // Yönetilebilir filtreler
    private val excludedTags = listOf("lgbt")

    // Kategori etiket kodları ve sıralaması
    private val categoryOrder = listOf(
        "çd", "diz", "ani", "yans", "pro", "bel", "kom", "mac", "çi", "yi",
        "sih", "yem", "sav", "ftb", "pemd", "müz", "giz", "kork", "eği", "dra", "gh",
        "tıp", "yar", "aks", "bilkur", "fant", "spor", "polis", "doğa", "suç", "füt"
    )

    // Etiket kodu -> açıklama
    private val tagLabels by lazy { runBlocking { loadTagLabels() } }

    // İçerik kodu -> etiket kodları
    private val contentTags by lazy {
        runBlocking {
            val diziTags = loadContentTagMappings("dizi")
            val filmTags = loadContentTagMappings("film")
            diziTags + filmTags
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/dizi" to "Diziler",
        *categoryOrder.map { code ->
            "$mainUrl/etiket/$code" to tagLabels[code].orEmpty()
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(listOf())
        Log.d("CizgiVeDizi", "getMainPage çağrıldı: ${request.name}")

        // Etiket bazlı listeleme: hem dizi hem film
        val tagCode = tagLabels.entries.firstOrNull { it.value == request.name }?.key
        if (tagCode != null) {
            val results = mutableListOf<SearchResponse>()

            // Diziler
            runCatching {
                val (diziKodList, diziIsimMap) = loadIsimData("dizi")
                val diziPosterMap = loadPosterData("dizi")
                diziKodList.filter { (code, _) -> contentTags[code]?.contains(tagCode) == true }
                    .forEach { (code, path) ->
                        if (contentTags[code]?.any { it in excludedTags } == true) return@forEach
                        val title = diziIsimMap[code] ?: return@forEach
                        val url = "$mainUrl/dizi/$code/$path"
                        val poster = diziPosterMap[code]?.let { fixImageFormat(it) }
                        results += newTvSeriesSearchResponse(title, url, TvType.Cartoon) {
                            this.posterUrl = poster
                        }
                    }
            }.onFailure { Log.e("CizgiVeDizi", "Dizi yükleme hatası", it) }

            // Filmler
            runCatching {
                val (filmKodList, filmIsimMap) = loadIsimData("film")
                val filmPosterMap = loadPosterData("film")
                filmKodList.filter { (code, _) -> contentTags[code]?.contains(tagCode) == true }
                    .forEach { (code, path) ->
                        if (contentTags[code]?.any { it in excludedTags } == true) return@forEach
                        val rawTitle = filmIsimMap[code] ?: return@forEach
                        val title = "$rawTitle (film)"
                        val url = "$mainUrl/film/$code/$path"
                        val poster = filmPosterMap[code]?.let { fixImageFormat(it) }
                        results += newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    }
            }.onFailure { Log.e("CizgiVeDizi", "Film yükleme hatası", it) }

            // Karışık listeleme için karıştır
            results.shuffle()

            return newHomePageResponse(request.name, results)
        }

        // Ana sayfa: sadece Diziler ana girdisi
        val results = runCatching {
            val (kodList, isimMap) = loadIsimData("dizi")
            val posterMap = loadPosterData("dizi")
            kodList.mapNotNull { (code, path) ->
                if (contentTags[code]?.any { it in excludedTags } == true) return@mapNotNull null
                val title = isimMap[code] ?: return@mapNotNull null
                val url = "$mainUrl/dizi/$code/$path"
                val poster = posterMap[code]?.let { fixImageFormat(it) }
                newTvSeriesSearchResponse(title, url, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }
        }.getOrElse {
            Log.e("CizgiVeDizi", "Ana sayfa yükleme hatası", it)
            emptyList()
        }

        return newHomePageResponse("Diziler", results)
    }

    // ─────────────────────────────────────────────────────────────
    // Parse yardımcıları — HTML/çöp filtreleme ile sağlamlaştırıldı
    // ─────────────────────────────────────────────────────────────

    /** Kod formatı: sadece küçük harf, rakam, tire, altçizgi. */
    private val validCodeRegex = Regex("^[a-z0-9_\\-]+$")

    /**
     * Bir değerin geçerli bir URL olup olmadığını kontrol eder.
     * HTML çöpü (tırnak, <, >, boşluk vb.) içerenleri reddeder.
     */
    private fun sanitizeUrl(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'', ',', ';')
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('"') || trimmed.contains('<') ||
            trimmed.contains('>') || trimmed.contains(' ') ||
            trimmed.contains('\n') || trimmed.contains('\t')
        ) return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }

    private suspend fun loadTagLabels(): Map<String, String> {
        val text = app.get("$mainUrl/etiket.txt").text
        // Eğer HTML döndüyse boş map
        if (text.trimStart().startsWith("<")) {
            Log.e("CizgiVeDizi", "etiket.txt HTML döndü, boş kabul ediliyor")
            return emptyMap()
        }
        return text.lineSequence()
            .map { it.trim().removePrefix("|") }
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val code  = parts[0].trim().lowercase()
                val label = parts[1].trim()
                if (!validCodeRegex.matches(code) || label.isEmpty()) return@mapNotNull null
                code to label
            }.toMap()
    }

    private suspend fun loadContentTagMappings(basePath: String): Map<String, List<String>> {
        val text = app.get("$mainUrl/$basePath/etiket.txt").text
        if (text.trimStart().startsWith("<")) {
            Log.e("CizgiVeDizi", "$basePath/etiket.txt HTML döndü, boş kabul ediliyor")
            return emptyMap()
        }
        return text.lineSequence()
            .map { it.trim().removePrefix("|") }
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val code = parts[0].trim().lowercase()
                if (!validCodeRegex.matches(code)) return@mapNotNull null
                val tags = parts[1].split(';')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && validCodeRegex.matches(it) }
                code to tags
            }.toMap()
    }

    private suspend fun loadIsimData(basePath: String): Pair<List<Pair<String, String>>, Map<String, String>> {
        val resp = app.get("$mainUrl/$basePath/isim.txt")
        val text = resp.text
        val list = mutableListOf<Pair<String, String>>()
        val map  = mutableMapOf<String, String>()

        // HTML döndüyse boş dön
        if (text.trimStart().startsWith("<")) {
            Log.e("CizgiVeDizi", "$basePath/isim.txt HTML döndü, boş kabul ediliyor")
            return list to map
        }

        text.lineSequence().forEach { line ->
            val cleaned = line.trim().removePrefix("|")
            val parts = cleaned.split('=', limit = 2)
            if (parts.size != 2) return@forEach

            val code = parts[0].trim().lowercase()
            val title = parts[1].trim()

            // Geçersiz kod veya boş başlık → atla
            if (!validCodeRegex.matches(code) || title.isEmpty()) return@forEach
            // HTML çöpü içeren başlıkları atla
            if (title.contains('<') || title.contains('>') || title.contains('"')) return@forEach

            list += code to title.replace(" ", "_")
            map[code] = title
        }
        return list to map
    }

    private suspend fun loadPosterData(basePath: String): Map<String, String> {
        val resp = app.get("$mainUrl/$basePath/poster.txt")
        val text = resp.text

        // HTML döndüyse boş dön (404 sayfası vs.)
        if (text.trimStart().startsWith("<")) {
            Log.e("CizgiVeDizi", "$basePath/poster.txt HTML döndü, boş kabul ediliyor")
            return emptyMap()
        }

        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val cleaned = line.trim().removePrefix("|")
            val parts = cleaned.split('=', limit = 2)
            if (parts.size != 2) return@forEach

            val code = parts[0].trim().lowercase()
            val raw  = parts[1].trim()

            // Kod geçerli mi?
            if (!validCodeRegex.matches(code)) return@forEach

            // URL geçerli mi? sanitizeUrl zaten HTML çöpünü reddeder
            val url = sanitizeUrl(raw) ?: return@forEach
            map[code] = url
        }
        return map
    }

    /**
     * Poster URL'ini Cloudinary fetch formatına çevirir.
     * Geçersiz/HTML içeren URL'lerde null döner.
     */
    private fun fixImageFormat(url: String): String? {
        if (url.isBlank()) return null
        if (!url.startsWith("http")) return null
        if (url.contains('"') || url.contains('<') || url.contains('>') || url.contains(' ')) return null

        return try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            "https://res.cloudinary.com/di0j4jsa8/image/fetch/f_auto/$encodedUrl"
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalizeString(query.lowercase().trim())
        val results = mutableListOf<SearchResponse>()

        for (basePath in listOf("dizi", "film")) {
            val (kodList, isimMap) = loadIsimData(basePath)
            val posterMap = loadPosterData(basePath)

            kodList.forEach { (code, _) ->
                val titlePlain = isimMap[code] ?: return@forEach
                if (!normalizeString(titlePlain.lowercase()).contains(normalizedQuery)) return@forEach
                if (contentTags[code]?.any { it in excludedTags } == true) return@forEach

                val title = if (basePath == "film") "$titlePlain (film)" else titlePlain
                val formattedRaw = titlePlain.replace(" ", "_")
                val url = "$mainUrl/$basePath/$code/$formattedRaw"
                val poster = posterMap[code]?.let { fixImageFormat(it) }

                results += newTvSeriesSearchResponse(
                    title,
                    url,
                    if (basePath == "film") TvType.Movie else TvType.Cartoon
                ) {
                    this.posterUrl = poster
                }
            }
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val isMovie = url.contains("/film/")
        return if (!isMovie) loadSeries(doc, url) else loadMovie(doc, url)
    }

    private suspend fun loadSeries(doc: Document, url: String) = runCatching {
        val title = doc.selectFirst("div.infoLine h4")?.text()?.trim().orEmpty()
        val rawPoster = fixUrlNull(doc.selectFirst("picture img")?.attr("src"))
        val poster = rawPoster?.let { fixImageFormat(it) }
        val plot = doc.selectFirst("div.col-12 p")?.text()?.trim().orEmpty()
        val tags = doc.select(".hero > div:nth-child(2) > div:nth-child(3) > p:nth-child(1)")
            .flatMap { it.text().split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val episodes = doc.select("div.container a.bolum").mapNotNull { el ->
            val rawName = el.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val epName  = rawName.substringAfter(")").trim().ifEmpty { rawName }
            val href    = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val num     = Regex("^(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val seasonN = el.attr("data-sezon").toIntOrNull() ?: 1

            newEpisode(href) {
                this.name    = epName
                this.episode = num
                this.season  = seasonN
            }
        }

        newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
        }
    }.getOrNull()

    private suspend fun loadMovie(doc: Document, url: String) = runCatching {
        val rawTitle = doc.selectFirst("h1.fw-light")?.text()?.trim().orEmpty()
        val title = "$rawTitle (film)"
        val rawPoster = fixUrlNull(doc.selectFirst("picture img")?.attr("src"))
        val poster = rawPoster?.let { fixImageFormat(it) }
        val plot = doc.selectFirst(".lead")?.text()?.trim().orEmpty()
        val tags = doc.select(".hero > div:nth-child(2) > div:nth-child(3) > p:nth-child(1)")
            .flatMap { it.text().split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }.getOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val playPage = doc.selectFirst("a[href*='/play']")?.attr("href")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return false

        val playUrl = fixUrlNull(playPage) ?: return false

        return loadExtractor(playUrl, subtitleCallback, callback)
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains('"') || url.contains('<') || url.contains('>') || url.contains(' ')) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun normalizeString(input: String) = input
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace('-', ' ').replace('_', ' ').replace('.', ' ')
}
