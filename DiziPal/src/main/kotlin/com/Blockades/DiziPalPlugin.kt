package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalPlugin: Plugin() {
    override fun load(context: Context) {
        // Class ismi DiziPalOriginal olarak kaydediliyor
        registerMainAPI(DiziPalOriginal())
    }
}
