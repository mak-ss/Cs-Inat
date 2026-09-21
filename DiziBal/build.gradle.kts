// ! Bu araç @SAKLImavi tarafından | @Blockades için yazılmıştır.
version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "En popüler yabancı dizileri yeni bölümleriyle takip edin. HBO, Netflix, Apple TV+ yapımları Türkçe altyazılı olarak dizibaL'da!"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizibal.org&sz=128"
}
