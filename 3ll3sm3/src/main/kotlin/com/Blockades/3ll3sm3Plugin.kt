package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.Blockades.Ell3sm3

@CloudstreamPlugin
class Ell3sm3Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Ell3sm3())
    }
}
