// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class CizgiveDizi : MainAPI() {

    override var mainUrl = "https://cizgivedizi.com"
    override var name = "CizgiveDizi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    // Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return null
    }

    // Arama
    override suspend fun search(query: String): List<SearchResponse>? {
        return null
    }

    // Detay
    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    // Video linklerini çıkarma
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
