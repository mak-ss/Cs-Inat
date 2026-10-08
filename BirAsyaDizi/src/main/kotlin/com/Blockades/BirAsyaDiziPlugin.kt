// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BirAsyaDiziPlugin: Plugin() {
    override fun load(context: Context) {
        // 只注册主 API，提取器通过 loadExtractor 自动匹配
        registerMainAPI(BirAsyaDizi())
    }
}
