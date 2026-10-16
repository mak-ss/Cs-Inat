version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "Mahsun Sports ile kesintisiz canlı maç keyfi. Futbol, basketbol ve tüm spor müsabakaları HD kalitesinde."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1
    tvTypes = listOf(
        "LiveStream",
        "Others"
    )
    iconUrl = "https://pbs.twimg.com/profile_images/2072217023463956480/JSocAKnc_400x400.jpg"
}