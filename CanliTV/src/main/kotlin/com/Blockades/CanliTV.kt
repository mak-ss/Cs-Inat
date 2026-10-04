// CanliTV.kt
// CloudStream CanliTV uyarlaması - canli.txt tabanlı (gömülü kanal listesi)

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class CanliTV : MainAPI() {
    override var mainUrl = ""   // Harici playlist yok, kanallar gömülü
    override var name = "CanliTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    // canli.txt içeriğinden gömülü kanal listesi
    private val channelList = listOf(
        // ---------- Ulusal ----------
        Channel(
            name = "TRT 1",
            logo = "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6c/TRT_1_logo_%282012-2021%29.png/1280px-TRT_1_logo_%282012-2021%29.png",
            url = "https://trt.daioncdn.net/trt-1/master.m3u8?app=clean",
            group = "Ulusal"
        ),
        Channel(
            name = "TRT 2",
            logo = "https://upload.wikimedia.org/wikipedia/commons/1/19/TRT_2_logo.svg",
            url = "https://tv-trt2.medya.trt.com.tr/master.m3u8",
            group = "Ulusal"
        ),
        Channel(
            name = "NOW TV",
            logo = "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/db/NOW_TV_%28Turkey%29_wordmark-red.svg/3840px-NOW_TV_%28Turkey%29_wordmark-red.svg.png",
            url = "https://nowtv.daioncdn.net/nowtv/nowtv.m3u8?ce=3&app=65fa8997-2b2f-4529-85ea-84318628a608&st=Krot3qnno8o9qPdYvwBDlQ&e=1791119413",
            group = "Ulusal"
        ),
        Channel(
            name = "Star TV",
            logo = "https://www.freelogovectors.net/wp-content/uploads/2018/04/Star_TV_Logo.png",
            url = "https://dogus.daioncdn.net/startv/startv.m3u8?ce=3&app=a20ac41e-bdc3-4aa1-934d-26b484480ac9&ppid=392cd5349ceb3c0269af5d746a70d821&dfp_paln=...",
            group = "Ulusal"
        ),
        Channel(
            name = "Eurostar",
            logo = "https://www.eurostartv.com.tr/img/logo.png",
            url = "https://dogusdyg-eurostar.lg.mncdn.com/dogusdyg_eurostar/live.m3u8?st=UYwdUtsvdMAkpL7tlPBhmw&e=1791240105",
            group = "Ulusal"
        ),
        Channel(
            name = "ATV",
            logo = "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/45/Atv_logo_2010.svg/960px-Atv_logo_2010.svg.png",
            url = "https://trkvz.daioncdn.net/atv/atv_1080p.m3u8?e=1791150702&st=NUUshDkb_SakOuz6cPkyvg&sid=8t0je0jhd420&app=d1ce2d40-5256-4550-b02e-e73c185a314e&ce=3",
            group = "Ulusal"
        ),
        Channel(
            name = "ATV Avrupa",
            logo = "https://i.tmgrup.com.tr/aav/site/v1/i/atv-avrupa-logo.png",
            url = "https://trkvz-live.ercdn.net/atvavrupa/atvavrupa.m3u8?st=0yFsDpzJ-zPWSlKkPNqpVA&e=1791198825",
            group = "Ulusal"
        ),
        Channel(
            name = "Kanal D",
            logo = "https://upload.wikimedia.org/wikipedia/tr/archive/4/4e/20240406161353%21Kanal_D.png",
            url = "https://demiroren.daioncdn.net/kanald/kanald_1080p.m3u8?&sid=8t0jhsl20in0&app=da2109ea-5dfe-4107-89ab-23593336ed61&ce=3",
            group = "Ulusal"
        ),
        Channel(
            name = "Show TV",
            logo = "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cb/Show_TV_logo.svg/250px-Show_TV_logo.svg.png",
            url = "https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc856ef-4c68-4a94-bc87-37dfaaa66558&st=RBzhSuGauna0OGld-DJUVA&e=1664766175&tv=1",
            group = "Ulusal"
        ),
        Channel(
            name = "Show Türk",
            logo = "https://files.sikayetvar.com/lg/cmp/18/180633.png?1619205105",
            url = "https://ciner-live.ercdn.net/showturk/playlist.m3u8?e=1791160973&st=IQNUsrHxmujXpHqb2F51Rg&tv=1",
            group = "Ulusal"
        ),
        Channel(
            name = "TV8",
            logo = "https://img.tv8.com.tr/s/template/v2/img/tv8-logo.png",
            url = "https://tv8.daioncdn.net/tv8/tv8_1080p.m3u8?&sid=8t2jdq6uo23x&app=7ddc255a-ef47-4e81-ab14-c0e5f2949788&ce=3",
            group = "Ulusal"
        ),
        Channel(
            name = "A2",
            logo = "https://iatv.tmgrup.com.tr/site/v2/a2tv/i/a2tv-logo.png",
            url = "https://trkvz.daioncdn.net/a2tv/a2tv_1080p.m3u8?e=1791199241&st=rPy4uSq1WbkP0tJWrSl3TA&sid=8t2jkdeg0j6p&app=59363a60-be96-4f73-9eff-355d0ff2c758&ce=3",
            group = "Ulusal"
        ),
        Channel(
            name = "Teve2",
            logo = "https://thumb.wikimedia.org/wikipedia/tr/thumb/a/ae/Tv2_logo_%282026%29.png/120px-Tv2_logo_%282026%29.png",
            url = "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8t2jp66pnxby&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3",
            group = "Ulusal"
        ),
        Channel(
            name = "TV4",
            logo = "https://www.turkmedya.com.tr/assets/img/source/png/tv4.png?v=1115",
            url = "https://turkmedya-live.ercdn.net/tv4/tv4_720p.m3u8",
            group = "Ulusal"
        ),

        // ---------- Haber ----------
        Channel(
            name = "TRT Haber",
            logo = "https://upload.wikimedia.org/wikipedia/commons/f/fe/TRT_Haber_kurumsal_logo_%282013-2020%29.png",
            url = "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
            group = "Haber"
        ),
        Channel(
            name = "Habertürk",
            logo = "https://upload.wikimedia.org/wikipedia/commons/7/78/Haberturk_logo.png",
            url = "https://ciner.daioncdn.net/haberturktv/haberturktv_1080p.m3u8?sid=8t2k9vjtt1kg&app=c98ab0b0-50cc-495b-bb37-778e91f5ff5b&ce=3",
            group = "Haber"
        ),
        Channel(
            name = "CNN Türk",
            logo = "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/59/CNN_T%C3%BCrk_logo.svg/1280px-CNN_T%C3%BCrk_logo.svg.png",
            url = "https://live.duhnet.tv//S2/HLS_LIVE/cnnturknp/playlist.m3u8?&live=true&app=com.cnnturk&st=AjNiFFFIgsepnn3cUEvytg&e=1791165450",
            group = "Haber"
        ),
        Channel(
            name = "NTV",
            logo = "https://upload.wikimedia.org/wikipedia/commons/b/b5/NTV_logo.png",
            url = "https://dogus.daioncdn.net/ntv/ntv_1080p.m3u8?token=e8c0dec16bf84c8cc187224d13811c0e705fd2c58b25d7ee&sid=8t2knf3gjqr9&app=c68bddbe-3dbf-49f7-892a-93de5ae37f1f&ce=3",
            group = "Haber"
        )
    )

    // -------- Helpers --------
    private fun Channel.toLoadData(): LoadData {
        return LoadData(
            url = this.url,
            title = this.name,
            poster = this.logo,
            group = this.group,
            nation = "tr"
        )
    }

    // -------- Pages & Search --------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = channelList
            .groupBy { it.group }
            .map { (grp, items) ->
                val show = items.map { channel ->
                    val data = channel.toLoadData()
                    newLiveSearchResponse(
                        data.title,
                        data.toJson(),
                        type = TvType.Live
                    ) {
                        this.posterUrl = data.poster.nullIfBlank()
                        this.lang = data.nation.nullIfBlank() ?: "tr"
                    }
                }
                HomePageList(grp, show, isHorizontalImages = true)
            }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        return channelList
            .filter { it.name.lowercase().contains(q) }
            .map { channel ->
                val data = channel.toLoadData()
                newLiveSearchResponse(
                    data.title,
                    data.toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = data.poster.nullIfBlank()
                    this.lang = data.nation.nullIfBlank() ?: "tr"
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // -------- Load & Links --------
    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)

        val nationPretty = "» ${loadData.group} | ${loadData.nation} «"

        val recommendations = channelList
            .filter { it.group == loadData.group }
            .mapNotNull { channel ->
                val d = channel.toLoadData()
                if (d.title == loadData.title) null
                else newLiveSearchResponse(d.title, d.toJson(), type = TvType.Live) {
                    this.posterUrl = d.poster.nullIfBlank()
                    this.lang = d.nation.nullIfBlank() ?: "tr"
                }
            }.toMutableList()

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl = loadData.poster.nullIfBlank()
            this.plot = nationPretty
            this.tags = listOf(loadData.group, loadData.nation)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = fetchDataFromUrlOrJson(data)
        Log.d("IPTV", "loadData » $loadData")

        val isM3u8 = loadData.url.contains(".m3u8", ignoreCase = true)

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = loadData.title,
                url = loadData.url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val group: String,
        val nation: String
    )

    data class Channel(
        val name: String,
        val logo: String,
        val url: String,
        val group: String
    )

    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        return if (data.startsWith("{")) {
            parseJson<LoadData>(data)
        } else {
            val channel = channelList.firstOrNull { it.url == data }
                ?: throw RuntimeException("Kanal bulunamadı: $data")
            channel.toLoadData()
        }
    }
}

// -------- küçük util --------
private fun String?.nullIfBlank(): String? = this?.ifBlank { null }
