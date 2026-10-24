// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @JsonProperty("response") val response: String? = null,
    @JsonProperty("state")    val state:    Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchData(
    @JsonProperty("state")  val state:  Boolean?         = null,
    @JsonProperty("result") val result: List<SearchItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("title")          val title:         String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("used_title")     val usedTitle:     String? = null,
    @JsonProperty("slug")           val slug:          String? = null,
    @JsonProperty("used_slug")      val usedSlug:      String? = null,
    @JsonProperty("poster")         val poster:        String? = null,
    @JsonProperty("poster_url")     val posterUrl:     String? = null,
    @JsonProperty("face_url")       val faceUrl:       String? = null,
)
