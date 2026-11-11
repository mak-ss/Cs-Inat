package com.Blockades 

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.Blockades.Sinewix 

@CloudstreamPlugin
class SineWixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Sinewix()) // <-- doğru sınıf adı
    }
}
