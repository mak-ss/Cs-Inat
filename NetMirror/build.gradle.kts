version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "en"
    description = "NetMirror, Netflix gibi hizmetlerin arayüzünü taklit ederek abonelik gerektirmeden ücretsiz film ve dizi sunan, doğrulanmamış, üçüncü taraf bir video akış platformu ve içerik toplayıcısıdır."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://netmirror.gg/favicon.ico"
}