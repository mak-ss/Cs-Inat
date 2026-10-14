package com.horis.net77

import com.horis.net77.entities.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class Net77Provider : MainAPI() {

    override var name = "Net77Provider"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var mainUrl = "https://net77.cc"

    private val commonHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept"          to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer"         to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/search.php?s=action"  to "Action Movies & Hits",
        "$mainUrl/search.php?s=avatar"  to "Trending Titles",
        "$mainUrl/search.php?s=marvel"  to "Superhero Collection",
        "$mainUrl/search.php?s=netflix" to "Popular Specials"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val res = try {
            app.get(request.data, headers = commonHeaders, timeout = 15)
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        }

        val data = tryParseJson<SearchData>(res.text) ?: return null

        val items = data.searchResult.orEmpty().mapNotNull { r ->
            val id     = r.id    ?: return@mapNotNull null
            val title  = r.title ?: "Unknown"
            val poster = r.image?.toHttps()?.takeIf { it.isNotBlank() }
                ?: "https://imgcdn.kim/poster/v/$id.jpg"

            newMovieSearchResponse(title, "$title|$id", TvType.Movie) {
                this.posterUrl = poster
            }
        }

        if (items.isEmpty()) return null

        val section = HomePageList(request.name, items, isHorizontalImages = true)
        return newHomePageResponse(listOf(section), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?s=${query.encUrlEncode()}"
        val res = try {
            app.get(url, headers = commonHeaders, timeout = 15)
        } catch (e: Throwable) {
            e.printStackTrace()
            return emptyList()
        }

        val data = tryParseJson<SearchData>(res.text) ?: return emptyList()
        return data.searchResult.orEmpty().mapNotNull { r ->
            val id     = r.id    ?: return@mapNotNull null
            val title  = r.title ?: "Unknown"
            val poster = r.image?.toHttps()?.takeIf { it.isNotBlank() }
                ?: "https://imgcdn.kim/poster/v/$id.jpg"

            newMovieSearchResponse(title, "$title|$id", TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts  = url.split("|")
        val title  = if (parts.size > 1) parts[0] else "Net77 Title"
        val postId = if (parts.size > 1) parts[1] else url.trim('/')

        val posterUrl = "https://imgcdn.kim/poster/v/$postId.jpg"

        val epUrl = "$mainUrl/episodes.php?s=$postId"
        val epRes = try {
            app.get(epUrl, headers = commonHeaders, timeout = 15)
        } catch (_: Throwable) {
            null
        }

        val epData   = epRes?.text?.let { tryParseJson<EpisodesData>(it) }
        val episodes = epData?.episodes.orEmpty()

        if (episodes.isNotEmpty()) {
            val csEpisodes = episodes.mapIndexedNotNull { idx, ep ->
                val epId = ep.id ?: return@mapIndexedNotNull null
                val num  = ep.ep?.toIntOrNull() ?: (idx + 1)
                newEpisode(epId) {
                    this.name      = ep.t ?: "Episode $num"
                    this.season    = 1
                    this.episode   = num
                    this.posterUrl = ep.img?.toHttps() ?: posterUrl
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, csEpisodes) {
                this.posterUrl = posterUrl
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, postId) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.trim('/')
        val url       = "$mainUrl/playlist.php?id=$episodeId"

        val res = try {
            app.get(url, headers = commonHeaders, timeout = 15)
        } catch (_: Throwable) {
            return false
        }

        val playlists = tryParseJson<List<PlayList>>(res.text) ?: return false
        var foundAny  = false

        playlists.forEach { playlist ->
            playlist.sources?.forEach { source ->
                val rawFile = source.file ?: return@forEach
                val fileUrl = if (rawFile.startsWith("http")) rawFile else "$mainUrl$rawFile"
                val label   = source.label ?: "HD"

                val checkRes = try {
                    app.get(fileUrl, headers = commonHeaders, timeout = 10)
                } catch (_: Throwable) {
                    null
                }

                if (checkRes != null && checkRes.code == 200) {
                    val quality = getQualityFromName(label)
                    callback(
                        newExtractorLink(
                            source  = name,
                            name    = "$name [$label]",
                            url     = fileUrl,
                            type    = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.referer = "$mainUrl/"
                            this.headers = commonHeaders
                        }
                    )
                    foundAny = true
                }
            }

            playlist.tracks?.forEach { track ->
                val fileUrl = track.file?.toHttps() ?: return@forEach
                val kind    = track.kind ?: "subtitles"
                if (kind.contains("sub", true) || kind.contains("cap", true)) {
                    subtitleCallback(SubtitleFile(track.label ?: "Unknown", fileUrl))
                }
            }
        }

        return foundAny
    }

    private fun String.toHttps(): String = when {
        startsWith("//")    -> "https:$this"
        startsWith("http:") -> "https${substring(4)}"
        else                -> this
    }

    private fun String.encUrlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
