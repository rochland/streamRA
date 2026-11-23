package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class DizisolProvider : MainAPI() {
    override var mainUrl = "https://dizisol.com/"
    override var name = "DİZİSOL"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = listOf(mainPageOf(
        "Ana Sayfa" to "home", "Yeni Eklenenler" to "Yeni Eklenenler",
        "Yerli" to "Yerli", "Dram" to "Dram", "Komedi" to "Komedi",
        "Gerilim" to "Gerilim", "Aksiyon" to "Aksiyon", "Suç" to "Suç",
        "Korku" to "Korku", "Netflix" to "Netflix", "Disney+" to "Disney+",
        "Amazon Prime" to "Amazon Prime", "HBO Max" to "HBO Max", "TOD" to "TOD"
    ))

    private fun parseSSR(html: String): JSONObject? {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#__react_ssr__") ?: return null
        return runCatching { JSONObject(script.data().ifBlank { script.html() }) }.getOrNull()
    }

    private fun makeResponse(o: JSONObject): SearchResponse? {
        val id = o.optInt("id")
        val title = o.optString("title").ifBlank { o.optString("name") }
        if (id <= 0 || title.isBlank()) return null
        val tv = o.optString("media_type").equals("tv", true) || o.optString("mediaType").equals("tv", true)
        val url = "dizisol|$id|${if (tv) "tv" else "movie"}"
        val poster = o.optString("poster_path").takeIf { it.startsWith("http") }
            ?: o.optString("poster").takeIf { it.startsWith("http") }
        val date = o.optString("release_date").ifBlank { o.optString("first_air_date") }
        val year = date.take(4).toIntOrNull()
        return if (tv) newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            posterUrl = poster
            this.year = year
        } else newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = poster
            this.year = year
        }
    }

    private fun homeLists(html: String): List<HomePageList> {
        val queries = parseSSR(html)?.optJSONObject("state")?.optJSONArray("queries") ?: return emptyList()
        for (i in 0 until queries.length()) {
            val q = queries.optJSONObject(i) ?: continue
            if (q.optJSONArray("queryKey")?.optString(0) != "library-home-feed") continue
            val rows = q.optJSONObject("state")?.optJSONObject("data")?.optJSONArray("rows") ?: continue
            val result = mutableListOf<HomePageList>()
            for (j in 0 until rows.length()) {
                val row = rows.optJSONObject(j) ?: continue
                val items = row.optJSONArray("items") ?: continue
                val cards = mutableListOf<SearchResponse>()
                for (k in 0 until items.length()) makeResponse(items.optJSONObject(k) ?: continue)?.let(cards::add)
                if (cards.isNotEmpty()) result.add(HomePageList(row.optString("title"), cards))
            }
            return result
        }
        return emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = homeLists(app.get(mainUrl).text)
        val chosen = if (request.data == "home") lists else lists.filter { it.name.equals(request.data, true) }
        return newHomePageResponse(chosen, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}arama?q=${URLEncoder.encode(query, "UTF-8")}"
        val html = app.get(url).text
        val out = linkedMapOf<String, SearchResponse>()
        val queries = parseSSR(html)?.optJSONObject("state")?.optJSONArray("queries") ?: JSONArray()
        fun walk(o: JSONObject) {
            val it = o.keys()
            while (it.hasNext()) {
                when (val v = o.opt(it.next())) {
                    is JSONArray -> for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i) ?: continue
                        makeResponse(item)?.let { out[item.optString("media_type") + item.optInt("id")] = it }
                    }
                    is JSONObject -> walk(v)
                }
            }
        }
        for (i in 0 until queries.length()) {
            val q = queries.optJSONObject(i) ?: continue
            q.optJSONObject("state")?.optJSONObject("data")?.let(::walk)
        }
        if (out.isEmpty()) {
            for (a in Jsoup.parse(html).select("a[href*=/film/],a[href*=/dizi/]")) {
                val href = a.absUrl("href")
                val title = a.text().trim()
                if (href.isBlank() || title.isBlank()) continue
                val data = "dizisol-url|$href"
                out[data] = if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, data, TvType.TvSeries)
                    else newMovieSearchResponse(title, data, TvType.Movie)
            }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val p = url.split("|")
        if (p.size < 3 || p[0] != "dizisol") return null
        val id = p[1].toIntOrNull() ?: return null
        val type = p[2]
        val detail = runCatching { JSONObject(app.get("${mainUrl}api/movies/by-tmdb/$id?type=$type").text) }.getOrNull() ?: return null
        val title = detail.optString("title").ifBlank { detail.optString("name") }.ifBlank { "DİZİSOL" }
        val poster = detail.optString("poster").takeIf { it.startsWith("http") }
        val plot = detail.optString("overview").takeIf { it.isNotBlank() }
        val year = detail.optInt("year").takeIf { it > 0 }
        val data = "dizisol-play|$id|$type"
        if (type == "tv") {
            val eps = runCatching { JSONArray(app.get("${mainUrl}api/movies/by-tmdb/$id/episodes").text) }.getOrNull() ?: JSONArray()
            val episodes = mutableListOf<Episode>()
            for (i in 0 until eps.length()) {
                val ep = eps.optJSONObject(i) ?: continue
                val season = ep.optInt("season")
                val number = ep.optInt("episode")
                if (season <= 0 || number <= 0) continue
                episodes.add(newEpisode("dizisol-episode|$id|$season|$number") {
                    name = ep.optString("title").ifBlank { "Bölüm $number" }
                    this.season = season
                    this.episode = number
                    description = ep.optString("overview").takeIf { it.isNotBlank() }
                })
            }
            return newTvSeriesLoadResponse(title, data, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        return newMovieLoadResponse(title, data, TvType.Movie, data) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val p = data.split("|")
        val detail = when (p.firstOrNull()) {
            "dizisol-play" -> {
                val id = p.getOrNull(1)?.toIntOrNull() ?: return false
                val type = p.getOrNull(2) ?: "movie"
                runCatching { JSONObject(app.get("${mainUrl}api/movies/by-tmdb/$id?type=$type").text) }.getOrNull()
            }
            "dizisol-episode" -> {
                val id = p.getOrNull(1)?.toIntOrNull() ?: return false
                val season = p.getOrNull(2)?.toIntOrNull() ?: return false
                val episode = p.getOrNull(3)?.toIntOrNull() ?: return false
                runCatching { JSONObject(app.get("${mainUrl}api/movies/by-tmdb/$id?season=$season&episode=$episode&type=tv").text) }.getOrNull()
            }
            else -> null
        } ?: return false
        var found = false
        val sources = detail.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            if (!s.optBoolean("isActive", true)) continue
            val stream = s.optString("m3u8Url")
            if (!stream.startsWith("http")) continue
            val provider = s.optString("provider").ifBlank { "Kaynak" }
            callback(newExtractorLink("DİZİSOL", provider, stream, type = ExtractorLinkType.M3U8) { referer = mainUrl })
            s.optString("subtitleTr").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("Turkish", it)) }
            s.optString("subtitleEn").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("English", it)) }
            found = true
        }
        if (!found) {
            val stream = detail.optString("m3u8Url")
            if (stream.startsWith("http")) {
                callback(newExtractorLink("DİZİSOL", "DİZİSOL", stream, type = ExtractorLinkType.M3U8) { referer = mainUrl })
                found = true
            }
        }
        return found
    }
}
