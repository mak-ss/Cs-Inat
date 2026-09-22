
// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "Yabancı dizi ve film izleme platformu. Altyazılı yabancı dizi ve film izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizipod.com&sz=%size%"
}
