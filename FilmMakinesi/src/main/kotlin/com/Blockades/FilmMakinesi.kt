package com.Blockades

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FilmMakinesi — filmmakinesi.co (WordPress "movifox" teması). Liste kartları `a.poster`,
 * arama `/?s=`, sayfalama `/page/N`. Detaydaki `oynatloload.top/embed/<id>` iframe'i
 * `__ULTRA_EAUTH` JWT'sini (cookie'si de aynı) taşır; `/api/video-bilgi/<id>?eauth=` imzalı
 * HLS master + kalite varyantları + VTT altyazıları döner. Alternatif detay sayfaları
 * (`/<slug>/2/`) vidmoly gibi ikinci bir kaynak ekler.
 *
 * Not: Common.kt kaldırıldı; tüm yardımcılar bu dosyada. Settings bağımlılığı yoktur.
 */
class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.co"
    private val uaHeaders get() = browserHeaders
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    // Settings.rowEnabled kaldırıldı: tüm satırlar her zaman gösterilir.
    override val mainPage get() = ROW_BASES.keys.map { row ->
        MainPageData(row, "$mainUrl${ROW_BASES.getValue(row)}")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = ROW_BASES[request.name] ?: request.data.removePrefix(mainUrl)
        // Ana sayfa rel=next ilan ediyor ama /page/N/ aynı 20 kartı döndürüyor.
        if (page > 1 && base.isEmpty()) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val url = if (page <= 1) "$mainUrl$base/" else "$mainUrl$base/page/$page/"
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = base.isNotEmpty() && items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("strong.poster-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img.wp-post-image")?.posterSrcLazy())
            this.posterHeaders = uaHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", headers = uaHeaders, params = mapOf("s" to query.trim())).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = "$mainUrl/?knl_ajax_search=1&s=" + java.net.URLEncoder.encode(q, "UTF-8")
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: return search(q)
        val ajaxHits = doc.select("a.search-result[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = a.selectFirst("span.title")?.text()?.trim()
                ?.ifBlank { null } ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(a.selectFirst("img")?.posterSrcLazy())
                this.posterHeaders = uaHeaders
            }
        }.distinctBy { it.url }
        return ajaxHits.ifEmpty { search(q) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val title = cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            .ifBlank { cleanTitle(doc.selectFirst("h1")?.text().orEmpty()) }
            .ifBlank { throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT") }
        val year = doc.selectFirst(".post-info-year-country a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
            ?: YEAR.find(doc.selectFirst("h1")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val poster = doc.selectFirst(".post-info-poster img")?.posterSrcLazy()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".post-info-content")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val duration = MINUTES.find(doc.selectFirst(".post-info-duration")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select(".post-info-cats a").map { it.text().trim() }.filter { it.isNotBlank() }
        val score = Score.from10(doc.selectFirst(".post-info-imdb-rating span")?.text()?.trim())

        // Oynatıcı iframe'leri yalnızca bu sayfada; alternatifler loadLinks'te taranır.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
            this.duration = duration
            this.tags = tags
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Settings.qualityAllowed kaldırıldı: tüm kaliteler iletilir.
        val linkCallback: (ExtractorLink) -> Unit = { callback(it) }
        // Settings.subtitleAllowed kaldırıldı: tüm altyazılar iletilir.
        val subtitleFilter: (SubtitleFile) -> Unit = { subtitleCallback(it) }
        val pageUrl = data.substringBefore("|")
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        val embeds = LinkedHashSet<String>()
        fun collect(page: Document) {
            page.select(".player-container iframe[src]").forEach { frame ->
                val src = frame.attr("src").trim()
                if (src.isNotBlank()) embeds.add(src)
            }
        }
        collect(doc)
        doc.select("a.alternative-link[href]")
            .map { fixUrl(it.attr("href")) }
            .filter { it.trimEnd('/') != pageUrl.trimEnd('/') }
            .take(MAX_ALTERNATES)
            .forEach { alt ->
                runCatching { app.get(alt, headers = uaHeaders).document }.getOrNull()?.let(::collect)
            }

        var found = false
        for (embed in embeds) {
            val ok = when {
                embed.contains("oynatloload.") -> resolveEmbed(embed, pageUrl, subtitleFilter, linkCallback)
                else -> loadExtractor(embed, pageUrl, subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** oynatloload embed -> E-AUTH JWT -> video-bilgi JSON -> imzalı HLS + altyazılar. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = EMBED_ID.find(embedUrl)?.groupValues?.get(1) ?: return false
        val page = runCatching { app.get(embedUrl, headers = uaHeaders, referer = referer) }.getOrNull()
            ?: return false
        // Token hem script içinde hem Set-Cookie'de; cookie jar'a güvenmek için ikisi de denenir.
        val token = EAUTH.find(page.text)?.groupValues?.get(1)
            ?: page.cookies[EAUTH_COOKIE]
            ?: return false
        val info = runCatching {
            JSONObject(
                app.get(
                    "$EMBED_HOST/api/video-bilgi/$id",
                    headers = uaHeaders,
                    referer = embedUrl,
                    params = mapOf("eauth" to token),
                ).text
            )
        }.getOrNull() ?: return false

        var found = false
        val master = info.optString("src").trim()
        val hasAudioTracks = (info.optJSONArray("audio_tracks")?.length() ?: 0) > 0
        val sources = info.optJSONArray("sources")
        if (master.isNotBlank() && (hasAudioTracks || sources == null || sources.length() == 0)) {
            callback(
                newExtractorLink(name, "$name Auto", master, ExtractorLinkType.M3U8) {
                    this.referer = "$EMBED_HOST/"
                    this.headers = uaHeaders + mapOf("Origin" to EMBED_HOST)
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        } else if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val file = source.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
                val label = source.optString("label").trim().ifBlank { "auto" }
                callback(
                    newExtractorLink(name, "$name $label", file, ExtractorLinkType.M3U8) {
                        this.referer = "$EMBED_HOST/"
                        this.headers = uaHeaders + mapOf("Origin" to EMBED_HOST)
                        this.quality = qualityOf(label)
                    }
                )
                found = true
            }
        }

        val subtitles = info.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val subtitle = subtitles.optJSONObject(i) ?: continue
                val file = subtitle.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
                val label = subtitle.optString("label").trim().ifBlank { "Altyazı" }
                val url = if (file.startsWith("http")) file else EMBED_HOST + file
                subtitleCallback(newSubtitleFile(label, url))
            }
        }
        return found
    }

    /** "Onun Evi Filmi 1080p Full HD izle" -> "Onun Evi"; "X izle (2026)" -> "X". */
    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(FILMI_SUFFIX, "")
        .replace(IZLE_SUFFIX, "")
        .replace(YEAR_SUFFIX, "")
        .trim()

    /** sources label'ı ("480p"/"900p"/"auto") sayısal kaliteye çevrilir. */
    private fun qualityOf(label: String): Int =
        label.filter { it.isDigit() }.toIntOrNull()?.takeIf { it >= 240 } ?: Qualities.Unknown.value

    companion object {
        private const val EMBED_HOST = "https://oynatloload.top"
        private const val EAUTH_COOKIE = "ultra_embed_auth"
        private const val MAX_ALTERNATES = 3
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val ROW_BASES = linkedMapOf(
            "Yeni Filmler" to "",
            "Popüler Filmler" to "/populer-filmler",
            "2026 Filmleri" to "/yil/2026",
            "Aksiyon" to "/aksiyon-filmleri-hd-izle",
            "Bilim Kurgu" to "/bilim-kurgu-filmleri-hd-izle",
            "Komedi" to "/komedi-fimleri-hd-izle",
            "Korku" to "/korku-filmleri-hd-izle",
            "Yerli Filmler" to "/yerli-filmleri-hd-izle",
        )
        val ROWS: List<String> = ROW_BASES.keys.toList()

        private const val CARD = ".posters-4-col a.poster"
        private val EMBED_ID = Regex("""/embed/(\d+)""")
        private val EAUTH = Regex("""__ULTRA_EAUTH\s*=\s*\"([^\"]+)\"""")
        private val FILMI_SUFFIX = Regex("""\s+Filmi\b.*$""")
        private val IZLE_SUFFIX = Regex("""\s+izle\b.*$""")
        private val YEAR_SUFFIX = Regex("""\s*\(\d{4}\)\s*$""")
        private val YEAR = Regex("""\((\d{4})\)""")
        private val MINUTES = Regex("""(\d+)\s*dakika""")
    }
}

// ---------------------------------------------------------------------------
// Common.kt içeriği (tek dosyaya gömüldü; com.Blockades paketi yok)
// ---------------------------------------------------------------------------

// Tüm modüllerin kopyaladığı tarayıcı kimliği; UA rulajı tek yerden yapılır.
// NiceHttp app.defaultHeaders küçük harfli "user-agent" kullandığı için anahtarlar küçük harflidir.
val browserHeaders: Map<String, String> = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
    put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
    put("accept-language", "tr-TR,tr;q=0.9")
}

fun Element.posterSrcLazy(): String? =
    posterSrcLazyImpl()

private val PLACEHOLDER_IMG = Regex(
    """^(data:|about:|javascript:)|/(pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(png|gif|jpg|jpeg|svg|webp)""",
    RegexOption.IGNORE_CASE,
)
private val BG_URL = Regex("""url\(['"]?([^'")\s]+)['"]?\)""", RegexOption.IGNORE_CASE)

private fun Element.posterSrcLazyImpl(): String? {
    for (attrName in listOf("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "data-lazy", "data-bg", "data-background", "data-image", "data-poster", "src")) {
        val v = attr(attrName).trim()
        if (v.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(v)) return v
    }
    for (attrName in listOf("data-srcset", "srcset")) {
        val v = attr(attrName).trim().substringBefore(',').trim().substringBefore(' ').trim()
        if (v.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(v)) return v
    }
    val style = attr("style").trim()
    if (style.isNotEmpty()) {
        val bg = BG_URL.find(style)?.groupValues?.get(1)?.trim().orEmpty()
        if (bg.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(bg)) return bg
    }
    return null
}

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(cipherBytes)
    }

fun aesGcmDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray, tagBits: Int = 128): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, iv))
        doFinal(cipherBytes)
    }

fun aesGcmEncrypt(plainBytes: ByteArray, key: ByteArray, iv: ByteArray, tagBits: Int = 128): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, iv))
        doFinal(plainBytes)
    }

/** DiziPal/SelcukFlix RSC `enc` bloğu: PBKDF2-HMAC-SHA512(999, 256) + AES-256-CBC. */
fun pbkdf2AesCbcDecrypt(
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

/** CryptoJS/OpenSSL EVP_BytesToKey (2x MD5 -> 32 bayt anahtar) + AES-256-CBC (bePlayer/vidmixi). */
fun evpMd5AesCbcDecrypt(cipherBytes: ByteArray, password: String, saltHex: String, ivHex: String): String {
    val pw = password.toByteArray(Charsets.UTF_8)
    val salt = saltHex.hexToBytes()
    fun md5(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest()
    val first = md5(pw, salt)
    val key = (first + md5(first, pw, salt)).copyOf(32)
    return String(aesCbcDecrypt(cipherBytes, key, ivHex.hexToBytes()), Charsets.UTF_8)
}

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME="(\d{3,4})p?"""", RegexOption.IGNORE_CASE)

/** HLS master playlist içindeki #EXT-X-STREAM-INF kalite varyantlarını (yükseklik px, tam URL) olarak çıkarır. */
fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
    if (!m3u8Text.contains("#EXT-X-STREAM-INF") ||
        m3u8Text.contains("TYPE=AUDIO", ignoreCase = true) ||
        m3u8Text.contains("AUDIO=", ignoreCase = true)
    ) return emptyList()
    val base = runCatching { URI(masterUrl.trim()) }.getOrNull() ?: return emptyList()
    val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val out = ArrayList<Pair<Int, String>>()
    val seen = HashSet<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val height = HLS_RES.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: HLS_NAME.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            var j = i + 1
            while (j < lines.size && lines[j].startsWith("#")) j++
            if (j < lines.size) {
                val target = runCatching {
                    val raw = lines[j]
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else base.resolve(raw).toString()
                }.getOrNull()
                if (!target.isNullOrBlank() && height > 0 && seen.add("$height|$target")) {
                    out.add(height to target)
                }
                i = j
            }
        }
        i++
    }
    return out.sortedByDescending { it.first }
}

/**
 * pichive iframe'i `nm.php`/`nsub.php` adreslerine proof-of-work ister (`&p=<nonce>`), yoksa 404 döner.
 * Sayfadaki `NP_GATE` (önek `String.fromCharCode`, sayaç base36, `& maske`) ya da varsayılan biçim
 * (`sha256("n:i")` ilk `b` biti sıfır) çözülür.
 */
fun pichiveGateNonce(html: String): String? {
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

/** `nm.php`/`nsub.php` adresine [pichiveGateNonce] ile bulunan `&p=` imzasını ekler; diğer adresler değişmez. */
fun pichiveSign(html: String, url: String): String {
    if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) return url
    val nonce = pichiveGateNonce(html) ?: return url
    return "$url&p=$nonce"
}

/**
 * Sayfadaki JSON-LD `description` alanını okur; FAQ şemasında "... konusu nedir?" cevabını tercih eder.
 * Meta açıklamaları SEO cümlesi olduğu için içerik özeti gereken yerlerde bu yardımcı kullanılır.
 */
fun Document.jsonLdDescription(): String? {
    val nodes = select("script[type=application/ld+json]").flatMap { element ->
        runCatching {
            val raw = element.data().trim()
            if (raw.startsWith("[")) {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                listOf(JSONObject(raw))
            }
        }.getOrDefault(emptyList())
    }.flatMap { it.ldNodes() }
    nodes.firstOrNull { it.optString("name").contains("konusu nedir", ignoreCase = true) }
        ?.optJSONObject("acceptedAnswer")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { return cleanJsonLd(it) }
    for (type in listOf("TVSeries", "Movie", "VideoObject", "CreativeWork", "Article")) {
        nodes.firstOrNull {
            it.optString("@type").contains(type, ignoreCase = true) && it.optString("description").isNotBlank()
        }?.let { return cleanJsonLd(it.optString("description")) }
    }
    return nodes.firstOrNull { it.optString("description").isNotBlank() }
        ?.let { cleanJsonLd(it.optString("description")) }
}

private fun JSONObject.ldNodes(): List<JSONObject> = buildList {
    add(this@ldNodes)
    for (key in listOf("@graph", "mainEntity")) {
        when (val child = opt(key)) {
            is JSONObject -> addAll(child.ldNodes())
            is JSONArray -> for (i in 0 until child.length()) {
                child.optJSONObject(i)?.let { addAll(it.ldNodes()) }
            }
        }
    }
}

private fun cleanJsonLd(raw: String): String = Jsoup.parse(raw).text().trim()

/** rapidvid `av('...')` kaynağı: ters base64 -> K9L kaydırması -> base64. */
fun rapidVidAvDecode(value: String): String? = runCatching {
    val first = String(
        android.util.Base64.decode(value.reversed(), android.util.Base64.DEFAULT),
        Charsets.ISO_8859_1,
    )
    String(android.util.Base64.decode(rapidVidShift(first), android.util.Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()

internal fun rapidVidShift(value: String): String = buildString(value.length) {
    val salt = "K9L"
    value.forEachIndexed { index, char ->
        append((char.code - (salt[index % salt.length].code % 5 + 1)).toChar())
    }
}
