package com.Blockades

import android.content.Context
import com.Blockades.DiziPalOrijinal          // ← EKLE
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPalOrijinalPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPalOrijinal())
    }
}
