
package com.example

import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl/"
    override var name = "HD Film Cehennemi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "${mainUrl}search/?q=$encodedQuery"
        ).text

        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val html = results.optString(i)
            val document = Jsoup.parse(html)

            for (item in document.select("a.search-result")) {
                val title = item.selectFirst("img")?.attr("alt")
                    ?.takeIf { it.isNotBlank() }
                    ?: continue

                val url = item.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: continue

                val poster = item.selectFirst("img")?.absUrl("src")
                    ?.takeIf { it.isNotBlank() }

                val year = item.selectFirst("span.year")
                    ?.text()
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()

                val isSeries = url.contains("/dizi/")

                if (isSeries) {
                    searchResults.add(
                        newTvSeriesSearchResponse(title, url) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    )
                } else {
                    searchResults.add(
                        newMovieSearchResponse(title, url) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    )
                }
            }
        }

        return searchResults
    }
}
