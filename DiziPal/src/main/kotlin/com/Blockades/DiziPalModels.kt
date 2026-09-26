// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty

data class DizipalSearchData(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("results") val results: List<DizipalSearchResult>?
)

data class DizipalSearchResult(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("rating") val rating: String?
)

// Next.js initial data modelleri
data class NextHeroItem(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("backdrop_url") val backdropUrl: String?,
    @JsonProperty("release_year") val releaseYear: Int?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("imdb_rating") val imdbRating: String?,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("_contentType") val contentType: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("short_description") val shortDescription: String?
)

data class NextEpisodeItem(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("series_id") val seriesId: Int?,
    @JsonProperty("series_title") val seriesTitle: String?,
    @JsonProperty("series_slug") val seriesSlug: String?,
    @JsonProperty("episode_title") val episodeTitle: String?,
    @JsonProperty("season_number") val seasonNumber: Int?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("duration") val duration: String?,
    @JsonProperty("added_date") val addedDate: String?,
    @JsonProperty("air_date") val airDate: String?
)
