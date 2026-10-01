
// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "Show TV canlı yayını, dizileri, eğlence programları, oyuncular ve daha fazlası sayfalarımızda. Hayat Türkiye'nin kanalı Show'la güzel! Şimdi Show TV canlı yayın izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.showtv.com.tr&sz=%size%"
}
