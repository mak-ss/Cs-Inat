package com.Blockades 

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.ulgencs3.sinewix.Sinewix // <-- doğru sınıfı import et

@CloudstreamPlugin
class SineWixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Sinewix()) // <-- doğru sınıf adı
    }
}
