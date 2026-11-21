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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// Ortak yardımcılar (eski Common.kt)
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
 * (`sha256("n:i")` ilk `b` biti sıfır) çözülür. shortcut: sayfa bu iki biçimden birini bırakırsa
 * nonce null olur ve adres imzasız kalır; yeni biçimde tekrar çözülmeli.
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

// ---------------------------------------------------------------------------
// Yabancıdizi sağlayıcısı
// ---------------------------------------------------------------------------

/** Yabancıdizi — yabancidizi.live */
class YabanciDizi : MainAPI() {
    override var mainUrl = "https://yabancidizi.live"
    private val uaHeaders get() = browserHeaders
    override var name = "Yabancıdizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Settings kaldırıldı; tüm satırlar her zaman görünür.
    override val mainPage get() = ROWS.map { name ->
        when (name) {
            "Son Eklenen Diziler" -> MainPageData(name, "$mainUrl/diziler?sort=date_desc")
            "Son Eklenen Filmler" -> MainPageData(name, "$mainUrl/filmler?sort=date_desc")
            "Diziler — Aksiyon" -> MainPageData(name, "$mainUrl/diziler?tur=aksiyon")
            "Diziler — Bilim Kurgu" -> MainPageData(name, "$mainUrl/diziler?tur=bilim-kurgu")
            "Filmler — Korku" -> MainPageData(name, "$mainUrl/filmler?tur=korku")
            else -> MainPageData(name, mainUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}&page=$page", headers = uaHeaders).document
        // Beyond the last page the site repeats the last one, so bound the count with nav.mpg.
        val lastPage = doc.select("nav.mpg a[href]").mapNotNull { a ->
            Regex("""[?&]page=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: page
        return newHomePageResponse(request.name, doc.cards(), hasNext = page < lastPage)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}", headers = uaHeaders).document.cards()

    /** Card anchors are shared by home, listings and search results. */
    private fun Document.cards(): List<SearchResponse> =
        select("a.movie-link[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val isMovie = href.contains("/film/")
            if (!isMovie && !href.contains("/dizi/")) return@mapNotNull null
            val title = a.selectFirst("h4")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }

    /** JSON-LD @graph node (TVSeries/Movie) — present on every detail page. */
    private fun Document.jsonLdNode(): JSONObject? {
        val json = selectFirst("script[type='application/ld+json']")?.data() ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val graph = obj.optJSONArray("@graph") ?: return obj
        return (0 until graph.length())
            .mapNotNull { graph.optJSONObject(it) }
            .firstOrNull { it.optString("@type") in setOf("TVSeries", "Movie") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val ld = doc.jsonLdNode()
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.substringBefore(" Türkçe")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(ld?.optString("image"))
            ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        val year = (ld?.optString("startDate")?.takeIf { it.isNotBlank() }
            ?: ld?.optString("datePublished"))?.take(4)?.toIntOrNull()
        val tags = ld?.optJSONArray("genre")
            ?.let { g -> (0 until g.length()).map { g.optString(it) } }
            .orEmpty()
        val score = Score.from10(
            Regex("""IMDb\s*([\d.]+)""")
                .find(doc.selectFirst(".watchParams__paramsList")?.text().orEmpty())
                ?.groupValues?.get(1)
                ?: doc.selectFirst(".rating-box")?.text()?.trim()
        )
        // Film JSON-LD carries e.g. "duration":"PT92M"; series pages have no runtime.
        val duration = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
            .find(ld?.optString("duration").orEmpty())?.let {
                (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
            }
        }

        val episodes = doc.select("a.v2-episode-row[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@mapNotNull null
            val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            newEpisode(href) {
                this.name = "Bölüm $number"
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
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
        // Settings kaldırıldı; tüm kalite ve altyazılar doğrudan iletilir.
        val linkCallback: (ExtractorLink) -> Unit = callback
        val subtitleFilter: (SubtitleFile) -> Unit = subtitleCallback
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val players = doc.select("iframe#player-iframe, [data-frame]").mapNotNull { el ->
            val url = el.attr("data-frame")
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("src") }
            url.takeIf { it.startsWith("http") }
        }.distinct()

        var found = false
        val seenLinks = HashSet<String>()
        val seenSubtitles = HashSet<String>()
        players.forEach { player ->
            runCatching {
                if (player.contains("/player/")) {
                    if (resolveObfuscatedPlayer(player, data, linkCallback, subtitleFilter, seenLinks, seenSubtitles)) {
                        found = true
                    }
                } else if (loadExtractor(player, data, subtitleFilter, linkCallback)) {
                    found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /**
     * The player page ships a reversed-base64 payload that is AES-CBC encrypted with
     * CryptoJS' OpenSSL KDF (MD5) and padded with junk tokens; the decrypted HTML
     * holds the master.m3u8 in its `file:` property.
     */
    private suspend fun resolveObfuscatedPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
    ): Boolean {
        val html = app.get(playerUrl, referer = referer, headers = uaHeaders).text
        val decoder = Regex("""var\s+(_\w+)\s*=\s*function\s*\(\s*s\s*\)\s*\{\s*return\s+atob\(\s*s\.split\(\s*''\s*\)\.reverse\(\)\.join\(\s*''\s*\)\s*\)\s*\}""")
            .find(html)?.groupValues?.get(1) ?: return false
        val parts = Regex(Regex.escape(decoder) + """\("([^"]*)"\)""")
            .findAll(html).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
        if (parts.size < 2) return false

        val junk = Regex("""\[\s*((?:"[^"]*"\s*,?\s*)+)\]\s*\.join\(\s*''\s*\)""")
            .find(html)?.groupValues?.get(1)
            ?.let { arr -> Regex("\"([^\"]*)\"").findAll(arr).joinToString("") { it.groupValues[1] } }
            .orEmpty()

        // atob(reverse(literal)) yields base64 text, which CryptoJS decodes again.
        val payload = decodeB64(parts[0].reversed()).toString(Charsets.UTF_8)
        val blob = decodeB64(payload)
        if (blob.size < 17 || !blob.copyOfRange(0, 8).contentEquals("Salted__".toByteArray())) return false

        val plain = openSslDecrypt(decodeB64(parts[1].reversed()), blob).replace(junk, "")
        val stream = Regex("""file\s*:\s*"([^"]+)"""").find(plain)?.groupValues?.get(1) ?: return false
        val base = java.net.URI(stream)

        // The master playlist carries every quality variant and both subtitle tracks; emit them all.
        val playlist = runCatching { app.get(stream, referer = playerUrl, headers = uaHeaders).text }.getOrNull()
        var found = false
        val variants = playlist?.lines()?.zipWithNext()?.mapNotNull { (line, next) ->
            if (!line.startsWith("#EXT-X-STREAM-INF") || next.startsWith("#")) return@mapNotNull null
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            runCatching { base.resolve(next.trim()).toString() }.getOrNull()?.let { it to height }
        }.orEmpty()

        for ((variant, height) in variants.distinctBy { it.first }) {
            if (!seenLinks.add(variant)) continue
            callback.invoke(
                newExtractorLink(this.name, "${this.name} ${height}p", variant, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = height
                }
            )
            found = true
        }
        if (!found && seenLinks.add(stream)) {
            callback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        val subtitleTracks = playlist?.lines()?.mapNotNull { line ->
            if (!line.startsWith("#EXT-X-MEDIA") || !line.contains("TYPE=SUBTITLES")) return@mapNotNull null
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val language = Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            runCatching { base.resolve(uri).toString() }.getOrNull()?.let { it to language }
        }.orEmpty()

        for ((subPlaylist, language) in subtitleTracks) {
            // Each sub playlist holds one VTT file; point the subtitle at it directly.
            val vtt = runCatching {
                app.get(subPlaylist, referer = playerUrl, headers = uaHeaders).text.lines()
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            }.getOrNull() ?: continue
            val subUrl = runCatching { java.net.URI(subPlaylist).resolve(vtt.trim()).toString() }.getOrNull()
                ?: continue
            if (!seenSubtitles.add(subUrl)) continue
            subtitleCallback.invoke(
                newSubtitleFile(language, subUrl) {
                    this.headers = mapOf("Referer" to playerUrl)
                }
            )
        }
        return found
    }

    /** Android's base64 decoder rejects unpadded input, and `atob` input is reversed. */
    private fun decodeB64(value: String): ByteArray =
        android.util.Base64.decode(
            value + "=".repeat((4 - value.length % 4) % 4),
            android.util.Base64.DEFAULT,
        )

    /** CryptoJS.AES.decrypt(passphrase, ciphertext): EVP_BytesToKey(MD5) + AES-256-CBC. */
    private fun openSslDecrypt(passphrase: ByteArray, blob: ByteArray): String {
        val salt = blob.copyOfRange(8, 16)
        val md = MessageDigest.getInstance("MD5")
        val derived = ByteArrayOutputStream()
        var block = ByteArray(0)
        while (derived.size() < 48) {
            md.reset()
            md.update(block)
            md.update(passphrase)
            md.update(salt)
            block = md.digest()
            derived.write(block)
        }
        val keys = derived.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.copyOfRange(0, 32), "AES"),
            IvParameterSpec(keys.copyOfRange(32, 48)),
        )
        return cipher.doFinal(blob.copyOfRange(16, blob.size)).toString(Charsets.UTF_8)
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live genre filter above. */
        val ROWS = listOf(
            "Son Eklenen Diziler", "Son Eklenen Filmler",
            "Diziler — Aksiyon", "Diziler — Bilim Kurgu", "Filmler — Korku",
        )
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
