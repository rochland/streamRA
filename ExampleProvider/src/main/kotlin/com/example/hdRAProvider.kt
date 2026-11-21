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

    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "${mainUrl}search/?q=$encodedQuery"

        val response = app.get(url).text
        val json = JSONObject(response)

        val results = json.optJSONArray("results")
            ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val html = results.optString(i)
            val document = Jsoup.parse(html)

            val item = document.select("a.search-result").first()
                ?: continue

            val title = item.select("h4.title").first()
                ?.text()
                ?: continue

            val itemUrl = item.absUrl("href")
            val poster = item.select("img").first()?.absUrl("src")

            val year = item.select(".year").first()
                ?.text()
                ?.toIntOrNull()

            val type = item.select(".type").first()
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
