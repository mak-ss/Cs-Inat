package com.UmayTrade

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StarTVPlugin: Plugin() {
    override fun load(context: Context) {
        // StarTv sınıf adıyla kaydediyoruz
        registerMainAPI(StarTv())
    }
}
