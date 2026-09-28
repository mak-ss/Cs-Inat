package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalPlugin : Plugin() {
    override fun load(context: Context) {
        // Main Provider Kaydı (DiziPalProvider veya DiziPal2 adındaki sınıfınız)
        registerMainAPI(DiziPal2())
        
        // Extractor Kayıtları (Cloudstream API'sinde ExtractorAPI şeklinde register edilir)
        registerExtractorAPI(DizipalPlayer2())
        registerExtractorAPI(FormationFeedExtractor())
    }
}
