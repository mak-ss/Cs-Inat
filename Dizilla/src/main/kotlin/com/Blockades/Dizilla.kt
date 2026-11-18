package com.Blockades

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// Common helpers (formerly Common.kt) — merged into this file.
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
// Dizilla provider
// ---------------------------------------------------------------------------

/**
 * Dizilla — dizilla.nl (301 → dizilla.club → dizilla.la). The pichive player on the
 * episode page still only accepts a dizilla.now Referer (see PLAYER_REFERER).
 * Next.js site: series metadata + episode list live in the JSON-LD blocks,
 * the episode page carries its player iframes in the RSC payload ("sources").
 */
class Dizilla : MainAPI() {
    private val uaHeaders get() = browserHeaders
    override var mainUrl = "https://dizilla.la"
    override var name = "Dizilla"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        when (name) {
            "Yabancı Diziler" -> MainPageData(name, "$mainUrl/yabanci-dizi-izle?page=SAYFA")
            "Yerli Diziler" -> MainPageData(name, "$mainUrl/yerli-dizi-izle?page=SAYFA")
            "K-Drama" -> MainPageData(name, "$mainUrl/kdrama-izle?page=SAYFA")
            "Tüm Diziler" -> MainPageData(name, "$mainUrl/dizi-izle?page=SAYFA")
            "Anime" -> MainPageData(name, "$mainUrl/anime-izle?page=SAYFA")
            "Türkçe Dublaj" -> MainPageData(name, "$mainUrl/turkce-dublaj-dizi-izle?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Cloudstream's HTTP layer preserves `+` as a literal plus in the query, so use %20.
        val url = "$mainUrl/ara?q=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}&page=$page"
        val doc = app.get(url, headers = uaHeaders).document
        return newSearchResponseList(doc.cards(), doc.hasNextPage())
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a.pcard").distinctBy { it.attr("href") }.mapNotNull { it.toSearchResult() }

    private fun Document.hasNextPage(): Boolean = selectFirst("a[rel=next]") != null

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("span.mvk-t")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("aria-label").substringBefore(",").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.posterSrcLazy()) ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching { app.get(url, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val doc = response.document
        // Flight payload escapes its JSON inside a JS string, so unescape before slicing.
        val rsc = response.text.replace("\\\"", "\"")
        val objects = doc.select("script[type=application/ld+json]")
            .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
            .filter { it.optString("@type") == "TVSeries" }
        val series = objects.maxByOrNull { countEpisodes(it) }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = LinkedHashMap<String, Episode>()
        objects.forEach { obj ->
            seasonsOf(obj).forEach { season ->
                val seasonNumber = season.optInt("seasonNumber", 1)
                val seasonEpisodes = season.optJSONArray("episode") ?: return@forEach
                for (index in 0 until seasonEpisodes.length()) {
                    val item = seasonEpisodes.optJSONObject(index) ?: continue
                    val episodeUrl = fixUrlNull(item.optString("url")) ?: continue
                    val episodeNumber = item.optInt("episodeNumber", index + 1)
                    episodes.getOrPut(episodeUrl) {
                        newEpisode(episodeUrl) {
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.name = "$seasonNumber. Sezon $episodeNumber. Bölüm"
                            this.description = item.optString("description").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }

        val title = series.optString("name").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val rating = series.optJSONObject("aggregateRating")?.optString("ratingValue")?.toDoubleOrNull()
        val poster = fixUrlNull(doc.selectFirst("img[src*='/images/tv/poster/']")?.attr("src"))
            ?: fixUrlNull(series.optString("image"))
        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
        ) {
            this.posterUrl = poster
            this.plot = series.optString("description").takeIf { it.isNotBlank() }
            this.year = doc.select("dl.frows dt").firstOrNull { it.text().contains("İlk yayın") }
                ?.nextElementSibling()?.text()?.trim()?.toIntOrNull()
            this.score = rating?.let { Score.from10(it) }
            this.duration = Regex("""PT(?:(\d+)H)?(\d+)M""").find(series.optString("timeRequired"))
                ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + it.groupValues[2].toInt() }
            this.actors = series.optJSONArray("actor")?.let { array ->
                (0 until array.length()).mapNotNull {
                    val name = array.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
                    name?.let { ActorData(Actor(it)) }
                }
            }?.takeIf { it.isNotEmpty() }
            this.recommendations = jsonArrayAfter(rsc, "\"similar\":[")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val relatedUrl = fixUrlNull(item.optString("url")) ?: return@mapNotNull null
                    newTvSeriesSearchResponse(title, relatedUrl, TvType.TvSeries) {
                        this.posterUrl = fixUrlNull(item.optString("poster"))
                    }
                }
            }
            jsonArrayAfter(rsc, "\"trailers\":[")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("ytId") }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { addTrailer("https://www.youtube.com/embed/$it") }
            }
        }
    }

    private fun seasonsOf(series: JSONObject): List<JSONObject> = when (val seasons = series.opt("containsSeason")) {
        is JSONObject -> listOf(seasons)
        is JSONArray -> (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
        else -> emptyList()
    }

    private fun countEpisodes(series: JSONObject): Int =
        seasonsOf(series).sumOf { it.optJSONArray("episode")?.length() ?: 0 }

    private data class Player(val url: String, val label: String, val quality: String, val lang: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Flight payload escapes its JSON inside a JS string, so unescape before slicing.
        val page = runCatching { app.get(data, headers = uaHeaders).text }.getOrNull()?.replace("\\\"", "\"")
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val players = jsonArrayAfter(page, "\"sources\":[")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                if (item.optBoolean("trailer")) return@mapNotNull null
                val src = item.optString("src").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                Player(
                    src,
                    item.optString("name"),
                    item.optString("quality"),
                    item.optString("langLabel").ifBlank { item.optString("lang") },
                )
            }
        }.orEmpty()
            // dub-1 and sub-1 often point at the same player, whose own audio menu holds both; merge the labels.
            .groupBy { it.url }
            .map { (_, entries) ->
                entries.first().copy(
                    lang = entries.map { it.lang }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                )
            }

        var found = false
        val seenSubtitles = HashSet<String>()
        players.forEach { player ->
            runCatching {
                if (loadPlayer(player, callback, subtitleCallback, seenSubtitles)) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** pichive iframe -> NP_CONFIG -> nm.php master playlist (+ nsub.php subtitles). */
    private suspend fun loadPlayer(
        player: Player,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenSubtitles: MutableSet<String>,
    ): Boolean {
        // pichive's allowlist still carries the old origin; the .la host is answered with 404 "7".
        val html = app.get(player.url, referer = PLAYER_REFERER, headers = uaHeaders).text
        val config = jsonObjectAfter(html, "window.NP_CONFIG") ?: return false
        val linkName = listOf(this.name, player.lang, player.label)
            .filter { it.isNotBlank() }.joinToString(" ")
        val quality = Regex("""(\d{3,4})""").find(player.quality)?.value?.toIntOrNull() ?: Qualities.Unknown.value

        var found = false
        val masters = config.optJSONArray("sources") ?: return false
        for (index in 0 until masters.length()) {
            val master = masters.optJSONObject(index)?.optString("master")
                ?.takeIf { it.startsWith("http") }?.let { pichiveSign(html, it) } ?: continue
            callback.invoke(
                newExtractorLink(this.name, linkName, master, ExtractorLinkType.M3U8) {
                    this.referer = player.url
                    this.quality = quality
                }
            )
            found = true
        }

        val subtitles = config.optJSONArray("subtitles")
        if (subtitles != null) {
            for (index in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(index) ?: continue
                val subtitleUrl = item.optString("url").takeIf { it.startsWith("http") }?.let { pichiveSign(html, it) } ?: continue
                if (!seenSubtitles.add(subtitleUrl)) continue
                val subtitleLang = item.optString("label").takeIf { it.isNotBlank() } ?: "Türkçe"
                subtitleCallback.invoke(
                    newSubtitleFile(subtitleLang, subtitleUrl) {
                        this.headers = mapOf("Referer" to player.url)
                    }
                )
            }
        }
        return found
    }

    private fun jsonArrayAfter(text: String, marker: String): JSONArray? {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return null
            val open = at + marker.length - 1
            val slice = sliceBalanced(text, open, '[', ']')
            if (slice != null) runCatching { JSONArray(slice) }.getOrNull()?.let { return it }
            from = open + 1
        }
    }

    private fun jsonObjectAfter(text: String, marker: String): JSONObject? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        val slice = sliceBalanced(text, open, '{', '}') ?: return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    private fun sliceBalanced(text: String, open: Int, openChar: Char, closeChar: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
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
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Yabancı Diziler",
            "Yerli Diziler",
            "K-Drama",
            "Tüm Diziler",
            "Anime",
            "Türkçe Dublaj",
        )

        /** The player's Referer allowlist accepts only this origin; the site itself moved to .la. */
        private const val PLAYER_REFERER = "https://dizilla.now/"

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
