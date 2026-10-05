package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PuhuTvPlugin : Plugin() {

    override fun load(context: android.content.Context) {
        registerMainAPI(PuhuTvProvider())
    }
}
