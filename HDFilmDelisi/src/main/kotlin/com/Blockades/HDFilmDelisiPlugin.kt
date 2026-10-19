package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmDelisiPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(HDFilmDelisi())
    }
}
