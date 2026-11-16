package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPalOriginalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPalOrijinal())
    }
}
