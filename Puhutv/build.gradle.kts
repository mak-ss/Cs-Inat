version = 14

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "tr"
    description = "PuhuTV - Yerli Dizi ve Film İzleme Platformu"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=puhutv.com&sz=%size%"
}