// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
version = 1

cloudstream {
    authors     = listOf("Blockades")
    language    = "mx"
    description = "Ver anime online, subtitulado y/o doblado al español latino HD y completamente gratis. Aquí podrás ver y descargar todas tus series preferidas sin anuncios.."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://ww1.henaojara.net/&size=128"
}
