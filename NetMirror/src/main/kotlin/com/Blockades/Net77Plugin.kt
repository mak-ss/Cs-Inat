package com.horis.net77

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
open class Net77Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Net77Provider())
    }
}
