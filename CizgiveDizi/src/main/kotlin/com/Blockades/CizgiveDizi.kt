// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

class CizgiveDizi : MainAPI() {

    override var mainUrl = "https://cizgivedizi.com" // Kendi sitenizin URL'sini yazın
    override var name = "CizgiveDizi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    // Ana sayfa içeriğini yükle
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Buraya ana sayfa mantığınızı yazın
        return null
    }

    // Arama fonksiyonu
    override suspend fun search(query: String): List<SearchResponse>? {
        // Buraya arama mantığınızı yazın
        return null
    }

    // Detay yükleme fonksiyonu
    override suspend fun load(url: String): LoadResponse? {
        // Buraya detay yükleme mantığınızı yazın
        return null
    }

    // Video linklerini çıkarma fonksiyonu
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buraya link çıkarma mantığınızı yazın
        return true
    }
}
