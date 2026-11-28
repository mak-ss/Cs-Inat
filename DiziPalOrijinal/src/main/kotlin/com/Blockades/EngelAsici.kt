package com.Blockades

import com.lagradost.cloudstream3.app

/**
 * Cloudstream'in global `app` (OkHttp tabanlı) istemcisine kısayol.
 * Tüm ağ çağrıları buradan geçer.
 */
object EngelAsici {
    val http get() = app
}
