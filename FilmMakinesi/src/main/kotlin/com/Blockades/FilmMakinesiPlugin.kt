// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmMakinesiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmMakinesi())
        registerExtractorAPI(FilmMakinesiPlayerResolver())
    }
}
