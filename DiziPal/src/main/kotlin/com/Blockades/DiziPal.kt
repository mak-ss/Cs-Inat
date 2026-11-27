package com.Blockades

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1598.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = mapOf("user-agent" to UA, "Accept-Language" to "tr-TR,tr;q=0.9")

    private val pagedSections = setOf("$mainUrl/yabanci-dizi-izle", "$mainUrl/film-izle")

    override val mainPage get() = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Yeni Diziler",
        "$mainUrl/film-izle" to "Yeni Filmler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/platform-bolumler/netflix" to "Netflix",
        "$mainUrl/platform-bolumler/prime" to "Amazon Prime",
        "$mainUrl/platform-bolumler/disneyplus" to "Disney+",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data in pagedSections
        val url = if (paged && page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = uaHeaders, referer = "$mainUrl/").document
        val items = doc.select("a.tc[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = paged && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val body = runCatching {
            app.post(
                "$mainUrl/api/mj",
                json = JSONObject().put("action", "searchContent").put("params", JSONArray().put(q)),
                headers = uaHeaders + mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama isteği başarısız. $REPORT_HINT")
        val rows = runCatching { JSONObject(body).optJSONArray("result") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
        return rows.objects().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = httpsUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders, referer = "$mainUrl/").document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val isSeries = pageUrl.contains("/series/")
        val ld = jsonLd(doc, if (isSeries) "TVSeries" else "Movie")
        val title = ld?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        // DÜZELTME: posterOf artık og:image ve kart görseline de düşüyor
        val poster = posterOf(doc, ld)

        if (isSeries) {
            val episodes = episodesOf(doc, ld)
            if (episodes.isEmpty()) {
                throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
                this.year = yearOf(ld)
                this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
                this.duration = minutes(ld?.optString("timeRequired"))
                this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
                this.recommendations = recommendationsOf(doc, isSeries)
                actorsOf(ld)?.let { addActors(it) }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = yearOf(ld)
            this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
            this.duration = minutes(ld?.optString("timeRequired"))
            this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
            this.recommendations = recommendationsOf(doc, isSeries)
            actorsOf(ld)?.let { addActors(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitles: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }

        val pageUrl = httpsUrl(data)
        val pageHtml = runCatching { app.get(pageUrl, headers = uaHeaders, referer = "$mainUrl/").text }
            .getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")

        val playerUrl = decryptEmbed(pageHtml)
            ?: throw ErrorLoadingException("$name: oynatıcı adresi çözülemedi (enc bloğu). $REPORT_HINT")
        val playerHtml = runCatching { app.get(playerUrl, headers = uaHeaders, referer = pageUrl).text }
            .getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")

        val found = npLinks(playerHtml, playerUrl, links, subtitles) ||
            dplayerLinks(playerHtml, playerUrl, links)

        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    // ---------------------------------------------------------------- listing

    /**
     * DÜZELTME: Dizipal kartları iki farklı yapıda gelir:
     *  - Trend/ana sayfa: <a class="tc"><span class="fr"><img ...></span><span><span style="...">Başlık</span><span>2025 · Suç</span></span></a>
     *  - Bazı listelerde eski sınıf: span.tt / span.ft1
     * Ayrıca poster lazy-load olduğu için src boş olabilir; data-dp-src / data-src fallback eklendi.
     */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val isSeries = href.contains("/series/")
        if (!isSeries && !href.contains("/movies/")) return null

        val title = selectFirst("span.tt")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("span.ft1")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("span[style*=font-size:15px]")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("span[style*=font-weight:700]")?.text()?.trim()?.ifBlank { null }
            ?: attr("aria-label").substringBefore(',').trim().ifBlank { null }
            ?: return null

        // DÜZELTME: lazy-load fallback
        val img = selectFirst("img")
        val poster = pickPosterSrc(img)

        val year = YEAR.find(text())?.value?.toIntOrNull()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val slug = optString("used_slug").trim().trimStart('/')
        if (slug.isEmpty()) return null
        val title = optString("object_name").trim().takeIf { it.isNotEmpty() } ?: return null

        val url = "$mainUrl/$slug"
        val poster = directImage(optString("object_poster_url"))
        val year = optString("object_release_year").toIntOrNull()?.takeIf { it in 1900..2100 }
        val score = Score.from10(optString("object_related_imdb_point").takeIf { it != "0" })

        return if (optString("used_type").equals("Series", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    /**
     * DÜZELTME: ampproject önbelleği yanında başka proxy'ler de olabilir.
     * Ayrıca `//host/path` şeklinde gelen görselleri de https'e çeviriyoruz.
     */
    private fun directImage(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val normalized = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> return null
        }
        return if (IMAGE_PROXY in normalized) {
            "https://" + normalized.substringAfter(IMAGE_PROXY)
        } else normalized
    }

    // ------------------------------------------------------------------ künye

    private fun episodesOf(doc: Document, ld: JSONObject?): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()

        // DÜZELTME: containsSeason hem obje hem array olabilir; ayrıca episode
        // doğrudan TVSeries altında da gelebilir.
        val seasons = when (val cs = ld?.opt("containsSeason")) {
            is JSONArray -> cs.objects()
            is JSONObject -> listOf(cs)
            else -> emptyList()
        }

        seasons.forEach { season ->
            val seasonNumber = season.optString("seasonNumber").toIntOrNull() ?: 1
            season.optJSONArray("episode").objects().forEach { entry ->
                val episodeUrl = httpsUrl(entry.optString("url"))
                if (episodeUrl.isEmpty()) return@forEach
                val number = entry.optString("episodeNumber").toIntOrNull()
                episodes[episodeUrl] = newEpisode(episodeUrl) {
                    this.name = entry.optString("name").trim()
                        .ifEmpty { "$seasonNumber. Sezon $number. Bölüm" }
                    this.season = seasonNumber
                    this.episode = number
                    this.description = entry.optString("description").trim().takeIf { it.isNotEmpty() }
                }
            }
        }

        // ld+json yoksa ya da boşsa: sayfadaki /bolum/ linklerinden çıkar
        if (episodes.isEmpty()) {
            doc.select("a[href*='/bolum/']").forEach { anchor ->
                val episodeUrl = httpsUrl(anchor.attr("href").substringBefore('?'))
                if (episodeUrl.isEmpty() || episodes.containsKey(episodeUrl)) return@forEach
                val match = EPISODE_IN_SLUG.find(episodeUrl) ?: return@forEach
                val season = match.groupValues[1].toIntOrNull() ?: return@forEach
                val number = match.groupValues[2].toIntOrNull()
                episodes[episodeUrl] = newEpisode(episodeUrl) {
                    this.name = "$season. Sezon $number. Bölüm"
                    this.season = season
                    this.episode = number
                }
            }
        }
        return episodes.values.toList()
    }

    private fun jsonLd(doc: Document, type: String): JSONObject? =
        doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == type }
        }

    /**
     * DÜZELTME: Öncelik sırası:
     *  1) /poster/ yolu (dizi detay sayfaları)
     *  2) og:image (film sayfaları ve bazı diziler)
     *  3) ld+json image
     *  4) sayfadaki ilk büyük kart görseli
     * Her adımda lazy-load fallback uygulanır.
     */
    private fun posterOf(doc: Document, ld: JSONObject?): String? {
        doc.selectFirst("img[src*=/poster/]")?.let { pickPosterSrc(it) }?.let { return it }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }?.let { return it }
        ld?.optString("image")?.takeIf { it.startsWith("http") }?.let { return it }
        doc.selectFirst("img[src*=/tv/brand/], img[src*=/movie/brand/]")?.let { pickPosterSrc(it) }?.let { return it }
        return doc.selectFirst("a.tc img")?.let { pickPosterSrc(it) }
    }

    private fun yearOf(ld: JSONObject?): Int? =
        ld?.optString("datePublished")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    private fun tagsOf(doc: Document): List<String> =
        doc.select("a[href^='/kategori/'], a[href^='/film-kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun recommendationsOf(doc: Document, isSeries: Boolean): List<SearchResponse> {
        val section = if (isSeries) "ds-ben" else "df-ben"
        return doc.select("section[aria-labelledby='$section'] a.tc[href]")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    private fun actorsOf(ld: JSONObject?): List<String>? =
        ld?.optJSONArray("actor").objects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }
            .takeIf { it.isNotEmpty() }

    private fun minutes(iso: String?): Int? {
        val match = ISO_DURATION.find(iso.orEmpty()) ?: return null
        val total = (match.groupValues[1].toIntOrNull() ?: 0) * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    // --------------------------------------------------------------- oynatıcı

    private fun decryptEmbed(html: String): String? {
        val fields = ENC_FIELD.findAll(html).groupBy({ it.groupValues[1] }) { it.groupValues[2] }
        val cipherText = fields["ciphertext"]?.firstOrNull() ?: return null
        val iv = fields["iv"]?.firstOrNull() ?: return null
        val salt = fields["salt"]?.firstOrNull() ?: return null

        // DÜZELTME: k alanı sayfada birden fazla geçebilir; hepsini dene.
        // Parola, ciphertext'in hemen yanındaki k olmalı; ama güvenli tarafta kalıp
        // her adayı deniyoruz. İlk başarılı çözüm döner.
        val candidates = fields["k"].orEmpty().filter { it.length >= 8 }.distinct()
        if (candidates.isEmpty()) return null

        for (passphrase in candidates) {
            val clear = pbkdf2AesCbcDecrypt(
                Base64.decode(cipherText, Base64.DEFAULT), passphrase, salt, iv
            ) ?: continue
            val url = httpsUrl(clear.trim('\t', '\r', '\n', ' ', '\u000f'))
            if (url.startsWith("http")) return url
        }
        return null
    }

    private suspend fun npLinks(
        playerHtml: String,
        playerUrl: String,
        emit: (ExtractorLink) -> Unit,
        emitSubtitle: (SubtitleFile) -> Unit,
    ): Boolean {
        val anchor = playerHtml.indexOf("window.NP_CONFIG")
        val open = if (anchor < 0) -1 else playerHtml.indexOf('{', anchor)
        if (open < 0) return false
        val config = objectAt(playerHtml, open)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false

        config.optJSONArray("subtitles").objects().forEach { track ->
            val url = track.optString("url").takeIf { it.startsWith("http") }?.let { pichiveSign(playerHtml, it) }
                ?: track.optString("tok").takeIf { it.isNotEmpty() }
                    ?.let { pichiveSign(playerHtml, "${ORIGIN.find(playerUrl)?.value.orEmpty()}/nsub.php?v=$it") }
                ?: return@forEach
            val label = track.optString("label").trim()
                .ifEmpty { track.optString("lang").trim() }
                .ifEmpty { "Altyazı" }
            emitSubtitle(SubtitleFile(label, url).apply {
                this.headers = mapOf("Referer" to playerUrl, "user-agent" to UA)
            })
        }

        var found = false
        config.optJSONArray("sources").objects().forEach { source ->
            val master = source.optString("master").takeIf { it.startsWith("http") }?.let { pichiveSign(playerHtml, it) } ?: return@forEach
            val label = source.optString("title").trim().ifEmpty { "Kaynak" }
            emitPlaylist(master, playerUrl, label, emit)
            found = true
        }
        return found
    }

    private suspend fun dplayerLinks(
        playerHtml: String,
        playerUrl: String,
        emit: (ExtractorLink) -> Unit,
    ): Boolean {
        val playlist = OPEN_PLAYER.find(playerHtml)?.groupValues?.get(1) ?: return false
        val origin = ORIGIN.find(playerUrl)?.value ?: return false
        val body = runCatching {
            app.get(
                "$origin/source2.php",
                params = mapOf("v" to playlist),
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = playerUrl,
            ).text
        }.getOrNull() ?: return false
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false

        var found = false
        root.optJSONArray("playlist").objects().forEach { item ->
            val label = item.optString("title").trim().ifEmpty { "Kaynak" }
            item.optJSONArray("sources").objects().forEach eachSource@{ source ->
                val file = source.optString("file").takeIf { it.startsWith("http") } ?: return@eachSource
                val master = if ("m.php" in file) file.replace("m.php", "master.m3u8") else file
                emitPlaylist(master, playerUrl, label, emit)
                found = true
            }
        }
        return found
    }

    private suspend fun emitPlaylist(
        masterUrl: String,
        referer: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
    ) {
        val playlist = runCatching { app.get(masterUrl, headers = uaHeaders, referer = referer).text }
            .getOrNull()
        val variants = parseVariants(playlist.orEmpty())
        if (variants.size <= 1) {
            emit(hlsLink(masterUrl, label, Qualities.Unknown.value, referer))
            return
        }
        variants.forEach { (height, url) ->
            val quality = height.takeIf { it > 0 } ?: Qualities.Unknown.value
            val variantLabel = if (quality > 0) "$label ${quality}p" else label
            emit(hlsLink(url, variantLabel, quality, referer))
        }
    }

    private fun parseVariants(playlist: String): List<Pair<Int, String>> {
        if (!playlist.startsWith("#EXTM3U") ||
            playlist.contains("TYPE=AUDIO", ignoreCase = true) ||
            playlist.contains("AUDIO=", ignoreCase = true)
        ) return emptyList()
        val variants = mutableListOf<Pair<Int, String>>()
        var height: Int? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                height = RESOLUTION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                continue
            }
            val pending = height ?: continue
            if (line.isEmpty() || line.startsWith("#")) continue
            variants.add(pending to line)
            height = null
        }
        return variants.distinctBy { it.second }
    }

    private suspend fun hlsLink(url: String, label: String, quality: Int, referer: String): ExtractorLink =
        newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = quality
            this.headers = uaHeaders
        }

    // ------------------------------------------------------------------ ortak

    /**
     * DÜZELTME: Lazy-load poster fallback.
     * Sıra: src → data-dp-src → data-src → data-lazy-src.
     * `#wtop` gibi fragmentler ve data: placeholder'lar temizlenir.
     */
    private fun pickPosterSrc(img: Element?): String? {
        if (img == null) return null
        for (attr in listOf("src", "data-dp-src", "data-src", "data-lazy-src")) {
            val raw = img.attr(attr).trim()
            if (raw.isEmpty()) continue
            val cleaned = raw.substringBefore('#').trim()
            if (cleaned.isEmpty()) continue
            if (cleaned.startsWith("data:")) continue
            if (cleaned.contains("placeholder", ignoreCase = true)) continue
            return if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
        }
        return null
    }

    private fun objectAt(html: String, from: Int): String? {
        if (from < 0 || from >= html.length || html[from] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in from until html.length) {
            val char = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(from, index + 1)
                }
            }
        }
        return null
    }

    /**
     * DÜZELTME: `http://` zorla `https://` yapılmıyor. Dizipal bazı CDN görselleri
     * http üzerinden verebiliyor; zorla çevirmek 404'e yol açabilir. Sadece
     * protokolsüz (`//`) adreslere https ekleniyor.
     */
    private fun httpsUrl(raw: String?): String {
        val url = raw?.trim().orEmpty()
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun Any?.seasons(): List<JSONObject> = when (this) {
        is JSONArray -> objects()
        is JSONObject -> listOf(this)
        else -> emptyList()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ============================================================ COMMON.KT'DEN TAŞINANLAR

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(cipherBytes)
        }

    private fun pbkdf2AesCbcDecrypt(
        cipherBytes: ByteArray,
        passphrase: String,
        saltHex: String,
        ivHex: String,
        rounds: Int = 999,
        keyBits: Int = 256,
    ): String? = runCatching {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), saltHex.hexToBytes(), rounds, keyBits))
            .encoded
        String(aesCbcDecrypt(cipherBytes, key, ivHex.hexToBytes()), Charsets.UTF_8)
    }.getOrNull()

    private fun pichiveGateNonce(html: String): String? {
        val gate = Regex(""""gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?""").find(html) ?: return null
        val n = gate.groupValues[1]
        val bits = gate.groupValues[2].toIntOrNull() ?: 12
        val md = MessageDigest.getInstance("SHA-256")
        fun wordAt(msg: String, idx: Int): Long {
            val d = md.digest(msg.toByteArray(Charsets.UTF_8))
            val o = (idx.coerceIn(0, 7)) * 4
            return ((d[o].toLong() and 0xff) shl 24) or ((d[o + 1].toLong() and 0xff) shl 16) or
                ((d[o + 2].toLong() and 0xff) shl 8) or (d[o + 3].toLong() and 0xff)
        }
        val fn = Regex("""window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};""").find(html)
        if (fn == null) {
            for (i in 0 until 50_000_000) if (wordAt("$n:$i", 0) ushr (32 - bits) == 0L) return i.toString()
            return null
        }
        val nVar = fn.groupValues[1]
        val body = fn.groupValues[3]
        val cm = Regex("""var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)""").find(body) ?: return null
        val saltVar = cm.groupValues[1]
        val salt = cm.groupValues[2].split(',').map { it.trim().toInt().toChar() }.joinToString("")
        val nonceMatch = Regex("""var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))""").find(body) ?: return null
        val nonceVar = nonceMatch.groupValues[1]
        val radix = nonceMatch.groupValues[2].toIntOrNull()?.coerceIn(2, 36) ?: 10
        val im = Regex("""\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0""").find(body) ?: return null
        val parts = im.groupValues[1].split('+').map { it.trim() }
        val wordIdx = im.groupValues[2].toIntOrNull() ?: 0
        val op = im.groupValues[3]
        val rhs = im.groupValues[4].toLongOrNull() ?: return null
        for (i in 0 until 50_000_000) {
            val nonce = java.lang.Long.toString(i.toLong(), radix)
            val msg = buildString {
                for (p in parts) {
                    when (p) {
                        nVar -> append(n)
                        saltVar -> append(salt)
                        nonceVar -> append(nonce)
                        else -> append(p.trim('\'', '"'))
                    }
                }
            }
            val w = wordAt(msg, wordIdx)
            val ok = if (op == ">>>") (w ushr rhs.toInt()) == 0L else (w and rhs) == 0L
            if (ok) return nonce
        }
        return null
    }

    private fun pichiveSign(html: String, url: String): String {
        if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) return url
        val nonce = pichiveGateNonce(html) ?: return url
        return "$url&p=$nonce"
    }

    companion object {
        val ROWS = listOf(
            "Yeni Diziler", "Yeni Filmler", "Trendler", "Keşfet",
            "Netflix", "Amazon Prime", "Disney+",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val IMAGE_PROXY = "/i/s/"

        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val EPISODE_IN_SLUG = Regex("""-(\d+)x(\d+)""")
        private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\s*\(\s*['"]([^'"]+)""")
        private val ORIGIN = Regex("""^https?://[^/]+""")

        private val ENC_FIELD = Regex("""\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)""")
    }
}
