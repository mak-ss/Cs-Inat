
// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.extractors.Mediafire
import com.lagradost.cloudstream3.extractors.OkRuSSL
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeYTXPlugin: Plugin() {
    override fun load() {
        registerMainAPI(AnimeYTX())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Mediafire())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(FireLoad())
        registerExtractorAPI(Ytplay())
        registerExtractorAPI(VidStack())
        registerExtractorAPI(Mytsumi())
        registerExtractorAPI(BurstCloud())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(Coflix())
        registerExtractorAPI(Embedseek())
    }
}