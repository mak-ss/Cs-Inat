package com.Blockades

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DizipalPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dizipal())
    }
}
