package com.Blockades

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal10.com"
    override var name = "Dizipal"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val playerPassphrase =
        "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "tr-TR,tr;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36",
    )

    private suspend fun fetchDocument(url: String): Document = runCatching {
        app.get(url, referer = "$mainUrl/", headers = browserHeaders).document
    }.getOrElse {
        Thread.sleep(1_200)
        app.get(url, referer = "$mainUrl/", headers = browserHeaders).document
    }

    private fun Document.catalogLinks(): Pair<List<Element>, List<Element>> {
        val movieHeading = select("h2").lastOrNull { it.text().contains("Son Eklenen Filmler", true) }
        val movieScope = movieHeading?.parent()?.parent() ?: this
        val movies = movieScope.select("a[data-moviess][href*=/film/]")
            .distinctBy { it.absUrl("href") }
        val episodes = select("a.mbb-episode[href*=/dizi/]")
            .distinctBy { it.absUrl("href") }
        return movies to episodes
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDocument(mainUrl)
        val (movieLinks, episodeLinks) = document.catalogLinks()
        val movies = movieLinks
            .take(18)
            .mapNotNull { it.toSearchResponse(TvType.Movie) }
        val series = episodeLinks
            .take(18)
            .mapNotNull { it.toSearchResponse(TvType.TvSeries) }

        return newHomePageResponse(
            listOf(
                HomePageList("Son Eklenen Filmler", movies, true),
                HomePageList("Güncel Diziler", series, true),
            ).filter { it.list.isNotEmpty() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim().lowercase()
        if (normalized.length < 2) return emptyList()
        val document = fetchDocument(mainUrl)
        val (movieLinks, episodeLinks) = document.catalogLinks()
        return (movieLinks + episodeLinks)
            .mapNotNull {
                val type = if (it.absUrl("href").contains("/film/")) TvType.Movie else TvType.TvSeries
                it.toSearchResponse(type)
            }
            .filter { it.name.lowercase().contains(normalized) }
            .take(40)
    }

    private fun Element.toSearchResponse(type: TvType): SearchResponse? {
        val url = absUrl("href").ifBlank { attr("href") }
        if (!url.startsWith(mainUrl)) return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim().orEmpty()
            .ifBlank { selectFirst("h4, h3, h2")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = image?.attr("data-src")?.ifBlank { image.attr("src") }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("$mainUrl/film/") && !url.startsWith("$mainUrl/dizi/")) return null
        val document = fetchDocument(url)
        val title = document.selectFirst("h1")?.text()?.substringBeforeLast(" izle")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[alt*=izle]")?.attr("src")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val playerUrl = resolvePlayer(document) ?: return null

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val season = Regex("(\\d+)[.-]?\\s*[Ss]ezon").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = Regex("(\\d+)[.-]?\\s*[Bb]ölüm").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newTvSeriesLoadResponse(
                title.replace(Regex("\\s+\\d+[.-]?\\s*[Ss]ezon.*$"), "").trim(),
                url,
                TvType.TvSeries,
                listOf(newEpisode(playerUrl) {
                    name = title
                    this.season = season
                    this.episode = episode
                    this.posterUrl = poster
                })
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun resolvePlayer(document: Document): String? {
        val encrypted = document.selectFirst("[data-rm-k]")?.text()?.trim()
            ?: return document.selectFirst("iframe[src]")?.absUrl("src")?.ifBlank { null }
        val payload = JSONObject(encrypted)
        val salt = payload.getString("salt").hexToBytes()
        val iv = payload.getString("iv").hexToBytes()
        val encryptedBytes = Base64.decode(payload.getString("ciphertext"), Base64.DEFAULT)
        val spec: KeySpec = PBEKeySpec(playerPassphrase.toCharArray(), salt, 999, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8).trim()
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadExtractor(data, mainUrl, subtitleCallback, callback)) return true

        val body = app.get(
            data,
            referer = mainUrl,
            headers = browserHeaders + ("Origin" to mainUrl)
        ).text
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        val playlistToken = Regex("window\\.openPlayer\\(['\"]([^'\"]+)")
            .find(body)?.groupValues?.getOrNull(1)
        if (!playlistToken.isNullOrBlank()) {
            val uri = URI(data)
            val playerOrigin = "${uri.scheme}://${uri.authority}"
            val sourceUrl = "$playerOrigin/source2.php?v=${URLEncoder.encode(playlistToken, "UTF-8")}"
            val sourcePayload = app.get(
                sourceUrl,
                referer = data,
                headers = browserHeaders + ("Origin" to playerOrigin)
            ).text
            val playlist = JSONObject(sourcePayload).optJSONArray("playlist")
            var emitted = false
            if (playlist != null) {
                for (itemIndex in 0 until playlist.length()) {
                    val sources = playlist.optJSONObject(itemIndex)?.optJSONArray("sources") ?: continue
                    for (sourceIndex in 0 until sources.length()) {
                        val source = sources.optJSONObject(sourceIndex) ?: continue
                        val stream = source.optString("file")
                            .replace("/m.php?", "/master.m3u8?")
                        if (stream.isBlank()) continue
                        val streamName = source.optString("title").ifBlank { "Yayın ${sourceIndex + 1}" }
                        callback(
                            newExtractorLink(name, "$name - $streamName", stream) {
                                referer = data
                                type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            }
                        )
                        emitted = true
                    }
                }
            }
            if (emitted) return true
        }

        val streams = Regex("https?://[^\\s\\\"'<>]+?\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>]*)?", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value }
            .distinct()
            .toList()

        streams.forEachIndexed { index, stream ->
            callback(
                newExtractorLink(name, "$name ${index + 1}", stream) {
                    referer = data
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }
        return streams.isNotEmpty()
    }
}
