package com.Blockades

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.providers.DizipalProvider // Import the provider

@CloudstreamPlugin
class DiziPalPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dizipal())
    }
}
