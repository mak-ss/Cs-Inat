// CizgiveDiziPlugin.kt
package com.Blockades

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class CizgiveDizi : MainAPI() {

    override var mainUrl = "https://cizgivedizi.com" // Kendi URL'nizi yazın
    override var name = "CizgiveDizi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"

    // Ana sayfa desteği
    override val hasMainPage = true

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
}
