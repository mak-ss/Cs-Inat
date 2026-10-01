package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.Blockades.ShowTv 

@CloudstreamPlugin
class ShowTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShowTv())
    }
}
