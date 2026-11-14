// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("tr_title") val trTitle: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("genres") val genres: String? = null,
    @JsonProperty("imdb") val imdb: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("view") val view: Int? = null,
    @JsonProperty("type") val type: String? = "defaultType",
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("baslik") val baslik: String? = null,
    @JsonProperty("alt") val alt: String? = null
)
