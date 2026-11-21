
package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class HDFilmCehennemiProvider : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl/"
    override var name = "HD Film Cehennemi"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "${mainUrl}search/?q=$encodedQuery"

        val response = app.get(url).text
        val json = JSONObject(response)
        val results = json.getJSONArray("results")

        val searchResults = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {

            val html = results.getString(i)
            val document = Jsoup.parse(html)

            val item = document.selectFirst("a.search-result")
                ?: continue

            val title = item.selectFirst("h4.title")
                ?.text()
                ?: continue

            val itemUrl = item.absUrl("href")
            val poster = item.selectFirst("img")
                ?.absUrl("src")

            val year = item.selectFirst(".year")
                ?.text()
                ?.toIntOrNull()

            val type = item.selectFirst(".type")
                ?.text()
                ?.trim()

            if (itemUrl.isBlank()) continue

            if (type.equals("Dizi", ignoreCase = true)) {

                searchResults.add(
                    newTvSeriesSearchResponse(
                        title,
                        itemUrl,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                        this.year = year
                    }
                )

            } else {

                searchResults.add(
                    newMovieSearchResponse(
                        title,
                        itemUrl,
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                        this.year = year
                    }
                )
            }
        }

        return searchResults
    }
}
