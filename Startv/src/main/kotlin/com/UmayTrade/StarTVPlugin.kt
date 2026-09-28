package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StarTVPlugin : Plugin() {
    override fun load(context: Context) {
        // Ana API'yi kaydet
        registerMainAPI(StarTv())
    }
}
