
// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "ATV'nin sevilen dizileri (Aşk ve Taht, Altı Üstü İstanbul) ve programları (Müge Anlı, Esra Erol) izleyin."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.atv.com.tr&sz=%size%"
}
