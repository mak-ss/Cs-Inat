version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "tr"
    description = "Tv2'de yayınlanan dizilerin tekrarları, yarışmalarının başvuru formları, programların eski bölümleri, canlı yayın izleme seçeneği ve çok daha fazlası tv2.com.tr'de ..."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://upload.wikimedia.org/wikipedia/tr/a/ae/Tv2_logo_%282026%29.png?utm_source=tr.wikipedia.org&utm_campaign=index&utm_content=original"
}
