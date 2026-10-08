// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.Blockades.OdnoklassnikiExtractor

@CloudstreamPlugin
class BirAsyaDiziPlugin: Plugin() {
    override fun load(context: Context) {
        // Ana içerik sağlayıcıyı kaydet
        registerMainAPI(BirAsyaDizi())
        // Odnoklassniki özel çıkarıcıyı kaydet
        registerExtractor(OdnoklassnikiExtractor())
    }
}
