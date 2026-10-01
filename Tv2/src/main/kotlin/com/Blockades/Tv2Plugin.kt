package com.Blockades   // ← Tv2.kt ile AYNI paket

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Tv2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Tv2())   // ← Tv2 sınıfını burada kullanıyor
    }
}
