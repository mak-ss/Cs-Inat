package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class 3ll3sm3Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(3ll3sm3())
    }
}