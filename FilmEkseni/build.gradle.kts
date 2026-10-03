version = 1

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "Film Ekseni Vizyonda ki, en güncel ve en yeni filmleri full hd kalitesinde türkçe dublaj ve altyazı seçenekleriyle 1080p olarak izleyebileceğiniz adresiniz."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT4qXkHyRVv3yot4iTXvTPBM8p0jLg69ko1Z8Z8UBmStg&s"
}
