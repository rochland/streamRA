package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl/"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val response = app.get("${mainUrl}search/?q=$encodedQuery").text
            val results = JSONObject(response).optJSONArray("results")
                ?: return emptyList()

            buildList {
                for (index in 0 until results.length()) {
                    val html = results.optString(index)
                    if (html.isBlank()) continue

                    val document = Jsoup.parse(html, mainUrl)
                    val item = document.selectFirst("a.search-result") ?: continue
                    val title = item.selectFirst("h4.title")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val itemUrl = item.absUrl("href").ifBlank { continue }
                    val poster = item.selectFirst("img")?.absUrl("src")
                        ?.takeIf { it.isNotBlank() }
                    val year = item.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
                    val type = item.selectFirst(".type")?.text()?.trim()

                    if (type.equals("Dizi", ignoreCase = true)) {
                        add(
                            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                                posterUrl = poster
                                this.year = year
                            }
                        )
                    } else {
                        add(
                            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                                posterUrl = poster
                                this.year = year
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
