package com.Blockades

import android.content.Context
import com.Blockades.EksenLoadExtractor
import com.Blockades.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmEkseniPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmEkseni())

        registerExtractorAPI(EksenLoadExtractor())
        registerExtractorAPI(VidMolyExtractor())
    }
}
