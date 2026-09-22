package com.Blockades

import android.content.Context
import com.Blockades.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziYoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziYo())

        registerExtractorAPI(VidMolyExtractor())
    }
}
