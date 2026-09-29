package com.UmayTrade

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StarTVPlugin : Plugin() {
    override fun load(context: Context) {
        Log.e("StarTvDebug", "=== STAR TV PLUGIN LOADED ===")
        registerMainAPI(StarTv())
    }
}
