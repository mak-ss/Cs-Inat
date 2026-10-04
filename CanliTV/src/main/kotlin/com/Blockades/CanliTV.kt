// CanliTV.kt
// CloudStream CanliTV uyarlaması - Blockades&Aras Uyarlaması ...

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
        Channel("TRT 1", "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6c/TRT_1_logo_%282012-2021%29.png/1280px-TRT_1_logo_%282012-2021%29.png", "https://trt.daioncdn.net/trt-1/master.m3u8?app=clean", "Ulusal"),
        Channel("TRT 2", "https://upload.wikimedia.org/wikipedia/commons/1/19/TRT_2_logo.svg", "https://tv-trt2.medya.trt.com.tr/master.m3u8", "Ulusal"),
        Channel("NOW TV", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/db/NOW_TV_%28Turkey%29_wordmark-red.svg/3840px-NOW_TV_%28Turkey%29_wordmark-red.svg.png", "https://nowtv.daioncdn.net/nowtv/nowtv.m3u8?ce=3&app=65fa8997-2b2f-4529-85ea-84318628a608&st=Krot3qnno8o9qPdYvwBDlQ&e=1791119413", "Ulusal"),
        Channel("Star TV", "https://www.freelogovectors.net/wp-content/uploads/2018/04/Star_TV_Logo.png", "https://dogus.daioncdn.net/startv/startv.m3u8?ce=3&app=a20ac41e-bdc3-4aa1-934d-26b484480ac9&ppid=392cd5349ceb3c0269af5d746a70d821&dfp_paln=...", "Ulusal"),
        Channel("Eurostar", "https://www.eurostartv.com.tr/img/logo.png", "https://dogusdyg-eurostar.lg.mncdn.com/dogusdyg_eurostar/live.m3u8?st=UYwdUtsvdMAkpL7tlPBhmw&e=1791240105", "Ulusal"),
        Channel("ATV", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/45/Atv_logo_2010.svg/960px-Atv_logo_2010.svg.png", "https://trkvz.daioncdn.net/atv/atv_1080p.m3u8?e=1791150702&st=NUUshDkb_SakOuz6cPkyvg&sid=8t0je0jhd420&app=d1ce2d40-5256-4550-b02e-e73c185a314e&ce=3", "Ulusal"),
        Channel("ATV Avrupa", "https://i.tmgrup.com.tr/aav/site/v1/i/atv-avrupa-logo.png", "https://trkvz-live.ercdn.net/atvavrupa/atvavrupa.m3u8?st=0yFsDpzJ-zPWSlKkPNqpVA&e=1791198825", "Ulusal"),
        Channel("Kanal D", "https://upload.wikimedia.org/wikipedia/tr/archive/4/4e/20240406161353%21Kanal_D.png", "https://demiroren.daioncdn.net/kanald/kanald_1080p.m3u8?&sid=8t0jhsl20in0&app=da2109ea-5dfe-4107-89ab-23593336ed61&ce=3", "Ulusal"),
        Channel("Euro D", "https://www.habersunum.com/uploads/tv/648-euro-d.jpg", "https://live.duhnet.tv/S2/HLS_LIVE/eurodnp/track_4_1000/playlist.m3u8", "Ulusal"),
        Channel("Show TV", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cb/Show_TV_logo.svg/250px-Show_TV_logo.svg.png", "https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc856ef-4c68-4a94-bc87-37dfaaa66558&st=RBzhSuGauna0OGld-DJUVA&e=1664766175&tv=1", "Ulusal"),
        Channel("Show Türk", "https://files.sikayetvar.com/lg/cmp/18/180633.png?1619205105", "https://ciner-live.ercdn.net/showturk/playlist.m3u8?e=1791160973&st=IQNUsrHxmujXpHqb2F51Rg&tv=1", "Ulusal"),
        Channel("TV8", "https://img.tv8.com.tr/s/template/v2/img/tv8-logo.png", "https://tv8.daioncdn.net/tv8/tv8_1080p.m3u8?&sid=8t2jdq6uo23x&app=7ddc255a-ef47-4e81-ab14-c0e5f2949788&ce=3", "Ulusal"),
        Channel("A2", "https://iatv.tmgrup.com.tr/site/v2/a2tv/i/a2tv-logo.png", "https://trkvz.daioncdn.net/a2tv/a2tv_1080p.m3u8?e=1791199241&st=rPy4uSq1WbkP0tJWrSl3TA&sid=8t2jkdeg0j6p&app=59363a60-be96-4f73-9eff-355d0ff2c758&ce=3", "Ulusal"),
        Channel("Teve2", "https://thumb.wikimedia.org/wikipedia/tr/thumb/a/ae/Tv2_logo_%282026%29.png/120px-Tv2_logo_%282026%29.png", "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8t2jp66pnxby&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3", "Ulusal"),
        Channel("TV4", "https://www.turkmedya.com.tr/assets/img/source/png/tv4.png?v=1115", "https://turkmedya-live.ercdn.net/tv4/tv4_720p.m3u8", "Ulusal"),

        // ---------- Haber ----------
        Channel("TRT Haber", "https://upload.wikimedia.org/wikipedia/commons/f/fe/TRT_Haber_kurumsal_logo_%282013-2020%29.png", "https://tv-trthaber.medya.trt.com.tr/master.m3u8", "Haber"),
        Channel("Habertürk", "https://upload.wikimedia.org/wikipedia/commons/7/78/Haberturk_logo.png", "https://ciner.daioncdn.net/haberturktv/haberturktv_1080p.m3u8?sid=8t2k9vjtt1kg&app=c98ab0b0-50cc-495b-bb37-778e91f5ff5b&ce=3", "Haber"),
        Channel("CNN Türk", "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/59/CNN_T%C3%BCrk_logo.svg/1280px-CNN_T%C3%BCrk_logo.svg.png", "https://live.duhnet.tv//S2/HLS_LIVE/cnnturknp/playlist.m3u8?&live=true&app=com.cnnturk&st=AjNiFFFIgsepnn3cUEvytg&e=1791165450", "Haber"),
        Channel("NTV", "https://upload.wikimedia.org/wikipedia/commons/b/b5/NTV_logo.png", "https://dogus.daioncdn.net/ntv/ntv_1080p.m3u8?token=e8c0dec16bf84c8cc187224d13811c0e705fd2c58b25d7ee&sid=8t2knf3gjqr9&app=c68bddbe-3dbf-49f7-892a-93de5ae37f1f&ce=3", "Haber"),
        Channel("Halk TV", "https://i.ibb.co/DYhmg6k/halk-tv.png", "https://halktv.daioncdn.net/halktv/halktv_1080p.m3u8?app=c86957d3-74a7-44da-9ad2-dc358c769609", "Haber"),
        Channel("TV100", "https://i.ibb.co/zZnBCWt/tv100.png", "https://tv100.daioncdn.net/tv100/tv100_1080p.m3u8?app=web", "Haber"),
        Channel("Tele1", "https://i.ibb.co/6yVTw6f/tele1.png", "https://tele1dvr.blutv.com/blutv_tele1_dvr2/live_720p2000000kbps/index.m3u8", "Haber"),

        // ---------- Müzik ----------
        Channel("Kral Pop", "https://i.ibb.co/vk0bKz0/kral-pop.png", "http://dygvideo.dygdigital.com/live/hls/kralpop", "Müzik"),
        Channel("TRT Müzik", "https://i.ibb.co/4gbPpzC/trt-muzik.png", "https://tv-trtmuzik.medya.trt.com.tr/master_720.m3u8", "Müzik"),
        Channel("Dream Türk", "https://i.ibb.co/fxRrKmB/dream-turk.png", "https://live.duhnet.tv/S2/HLS_LIVE/dreamturknp/playlist.m3u8", "Müzik"),
        Channel("Power Türk Taptaze", "https://i.ibb.co/F5K5xs9/powerturk-taptaze.png", "http://livetv.powerapp.com.tr/pturktaptaze/taptaze.smil/playlist.m3u8", "Müzik"),

        // ---------- Spor ----------
        Channel("TRT Spor", "https://i.ibb.co/WxD4PLG/trt-spor.png", "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8", "Spor"),
        Channel("A Spor", "https://i.ibb.co/rwnrgFV/a-spor.png", "https://trkvz.daioncdn.net/aspor/aspor_1080p.m3u8?e=1791201603&st=yIag8vwnpb2oTARMUlUxxA&sid=8t2n2d5a6yp4&app=45f847c4-04e8-419a-a561-2ebf87084765&ce=3", "Spor"),
        Channel("TRT Spor Yıldız", "https://i.ibb.co/YRgKcDn/trt-spor-yildiz.png", "https://tv-trtspor2.medya.trt.com.tr/master_720.m3u8", "Spor"),
        Channel("Fenerbahçe TV", "https://media.fenerbahce.org/FB/media/FB/Images/Logo/logo.png?ext=.png", "https://fbtv.fenerbahce.org/fenerbahcetv.stream/chunklist.m3u8", "Spor"),

        // ---------- Belgesel / Yaşam ----------
        Channel("TRT Belgesel", "https://i.ibb.co/MkvY46q/trt-belgesel.png", "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8", "Belgesel"),
        Channel("Yaban TV", "https://upload.wikimedia.org/wikipedia/tr/3/38/Yabantv_logo.png", "https://yayin1.canlitv.fun/canlitv/yabantv.stream/chunklist_w465167144.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),
        Channel("Köy TV", "https://www.digiturkburada.com.tr/kanal3/kanal-buyuk/koy-tv-buyuk.png?rkt=DfS6Tgv6Hjr93k3", "https://yayin1.canlitv.fun/canlitv/koytv.stream/chunklist_w1822809291.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),
        Channel("Çiftçi TV", "https://asset.artidijitalmedya.com/image/400x400/channels/v1/logo_25.png?v=11", "https://live.artidijitalmedya.com/artidijital_ciftcitv/ciftcitv/chunks.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),
        Channel("Toprak TV", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQR7Cm1M_XSKrHA3lT76muWIIlAfMNPmfTt0dleRVxorsoYoPW2Sf3Wl_TP&s=10", "https://yayin1.canlitv.fun/canlitv/topraktv.stream/chunklist_w922588126.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),
        Channel("DMAX", "https://i.ibb.co/1LH22gK/dmax.png", "https://yayin2.canlitv.fun/live/dmax.stream/chunklist_w112962807.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),
        Channel("TLC", "https://i.ibb.co/Cm1nYZK/tlc.png", "https://yayin2.canlitv.fun/live/tlc.stream/chunklist_w1209058347.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Belgesel"),

        // ---------- Çocuk ----------
        Channel("TRT Çocuk", "https://upload.wikimedia.org/wikipedia/commons/2/23/TRT_%C3%A7ocuk_logo.png", "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8", "Çocuk"),
        Channel("Cartoon Network", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/43/Cartoon_Network_logo_%282004-2010%29.jpg/330px-Cartoon_Network_logo_%282004-2010%29.jpg", "https://yayin2.canlitv.fun/live/cartoon-network.stream/chunklist_w1075014966.m3u8?hash=806fc487ca93f2adc6c452bc3c0d5403", "Çocuk"),
        Channel("MinikaGO", "https://upload.wikimedia.org/wikipedia/commons/8/8a/MinikaGO.png", "https://trkvz.daioncdn.net/minikago_cocuk/minikago_cocuk_720p.m3u8?e=1791203077&st=5aoqGDga7il5Uuafpowwqw&sid=8t2p9au28e9e&app=8e9e79c3-44e3-4a41-a921-1d8f0e7887c0&ce=3", "Çocuk")
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
