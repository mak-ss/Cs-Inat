package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.mebularts.DiziPal // DiziPal.kt dosyasındaki paketi import ediyoruz

@CloudstreamPlugin
class DiziPalPlugin: Plugin() {
    override fun load(context: Context) {
        // Ana sağlayıcıyı Cloudstream'e kaydediyoruz
        registerMainAPI(DiziPal())
    }
}
