version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "DiziAsia - Asya Dizileri İzle"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie","AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=diziasia.com&sz=%size%"
}