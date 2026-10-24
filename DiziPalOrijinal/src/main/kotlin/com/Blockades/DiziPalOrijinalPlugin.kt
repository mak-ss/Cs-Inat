package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalOrijinalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPalOrg())

        // Genel ContentX çözücü (alan adından bağımsız)
        registerExtractorAPI(ContentXGenel())

        // Siteye özel alan adları için isim eşleşmesi
        registerExtractorAPI(Hotlinger())
        registerExtractorAPI(FourCX())
        registerExtractorAPI(PlayRu())
        registerExtractorAPI(FourPlayRu())
        registerExtractorAPI(Pichive())
        registerExtractorAPI(FourPichive())
        registerExtractorAPI(FourDplayer())
        registerExtractorAPI(SNDplayer())
        registerExtractorAPI(ORGDplayer())
        registerExtractorAPI(Dplayer())
    }
}
