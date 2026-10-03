// Plugin sınıfı için güncellenmiş kod
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.Blockades.EmbedSporty
import com.Blockades.EmbedStreams
import com.Blockades.Streamed

@CloudstreamPlugin
class StreamedPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Streamed())
        registerExtractorAPI(EmbedStreams(context))
        registerExtractorAPI(EmbedSporty(context))
    }
}
