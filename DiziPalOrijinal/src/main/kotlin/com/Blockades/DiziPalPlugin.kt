package com.Blockades

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziPalPlugin : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(DiziPal())
        this.openSettings = { ctx ->
            Settings.showDialog(ctx, "DiziPal Ayarları", DiziPal.ROWS)
        }
    }
}
