package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.keyiflerolsun.DiziPal2

@CloudstreamPlugin
class DiziPal2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPal2())
    }
}
