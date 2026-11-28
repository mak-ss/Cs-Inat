package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPalOrijinalPlugin : Plugin() {
    override fun load(context: Context) {
        WebViewVekili.kur(context)
        registerMainAPI(DiziPalOrijinal())
        registerExtractorAPI(DiziPalOrijinalPlayer())
    }
}
