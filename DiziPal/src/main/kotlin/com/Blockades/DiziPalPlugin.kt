package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalPlugin: Plugin() {
    override fun load(context: Context) {
        // İki dosya da aynı pakette olduğu için doğrudan erişebilir
        registerMainAPI(DiziPal())
    }
}
