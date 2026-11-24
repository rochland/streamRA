package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer

class DizisolProvider : MainAPI() {
    override var mainUrl = "https://dizisol.com/"
    override var name = "DİZİSOL"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "Ana Sayfa" to "home", "Yeni Eklenenler" to "Yeni Eklenenler",
        "Yerli" to "Yerli", "Dram" to "Dram", "Komedi" to "Komedi",
        "Gerilim" to "Gerilim", "Aksiyon" to "Aksiyon", "Suç" to "Suç",
        "Korku" to "Korku", "Netflix" to "Netflix", "Disney+" to "Disney+",
        "Amazon Prime" to "Amazon Prime", "HBO Max" to "HBO Max", "TOD" to "TOD"
    )

    private fun ssr(html: String): JSONObject? {
        val script = Jsoup.parse(html, mainUrl).selectFirst("script#__react_ssr__") ?: return null
        return runCatching { JSONObject(script.data().ifBlank { script.html() }) }.getOrNull()
    }

    private fun poster(value: String?): String? = when {
        value.isNullOrBlank() || value == "null" -> null
        value.startsWith("http") -> value
        value.startsWith("/") -> "https://image.tmdb.org/t/p/w500$value"
        else -> null
    }

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "").replace("[^a-z0-9]+".toRegex(), " ").trim()

    private fun canonicalMap(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val doc = Jsoup.parse(html, mainUrl)
        for (a in doc.select("a[href*=/film/], a[href*=/dizi/], a[href*=/izle/film/], a[href*=/izle/dizi/]")) {
            val href = a.absUrl("href")
            if (!href.startsWith(mainUrl)) continue
            val candidates = listOf(
                a.selectFirst("h1,h2,h3,h4")?.text().orEmpty(),
                a.text(),
                a.selectFirst("img")?.attr("alt").orEmpty(),
                a.selectFirst("img")?.attr("title").orEmpty()
            )
            for (candidate in candidates) {
                val key = normalizeTitle(candidate)
                if (key.length >= 3) result.putIfAbsent(key, href)
            }
        }
        return result
    }

    private fun response(o: JSONObject, fallback: String? = null, canonical: Map<String, String> = emptyMap()): SearchResponse? {
        val id = o.optInt("id").takeIf { it > 0 } ?: o.optInt("tmdbId").takeIf { it > 0 } ?: return null
        val media = o.optString("media_type").ifBlank { o.optString("mediaType") }.ifBlank { fallback.orEmpty() }
        val tv = media.equals("tv", true) || media.equals("series", true) ||
            (media.isBlank() && (o.has("first_air_date") || o.has("name")))
        val titleTr = o.optString("titleTr").takeIf { it.isNotBlank() && it != "null" }
        val title = titleTr
            ?: o.optString("title").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val image = poster(o.optString("poster_path").ifBlank { o.optString("poster") })
        val year = o.optString("release_date").ifBlank { o.optString("first_air_date") }.take(4).toIntOrNull()
            ?: o.optInt("year").takeIf { it > 0 }
        val url = listOfNotNull(titleTr, o.optString("title").takeIf { it.isNotBlank() && it != "null" }, o.optString("name").takeIf { it.isNotBlank() && it != "null" }).firstNotNullOfOrNull { canonical[normalizeTitle(it)] } ?: "dizisol|$id|${if (tv) "tv" else "movie"}"
        return if (tv) newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            posterUrl = image; this.year = year
        } else newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = image; this.year = year
        }
    }

    private fun collect(value: Any?, out: MutableMap<String, SearchResponse>, fallback: String? = null, canonical: Map<String, String> = emptyMap()) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), out, fallback, canonical)
            is JSONObject -> {
                val item = response(value, fallback, canonical)
                if (item != null) {
                    val id = value.optInt("id").takeIf { it > 0 } ?: value.optInt("tmdbId")
                    out["$fallback:$id"] = item
                } else {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val kind = when (key.lowercase()) {
                            "tv", "series", "diziler" -> "tv"
                            "movies", "movie", "films", "filmler" -> "movie"
                            else -> fallback
                        }
                        collect(value.opt(key), out, kind, canonical)
                    }
                }
            }
        }
    }

    private fun homeLists(html: String): List<HomePageList> {
        val queries = ssr(html)?.optJSONObject("state")?.optJSONArray("queries") ?: return emptyList()
        val canonical = canonicalMap(html)
        val result = linkedMapOf<String, MutableList<SearchResponse>>()
        for (i in 0 until queries.length()) {
            val q = queries.optJSONObject(i) ?: continue
            if (q.optJSONArray("queryKey")?.optString(0) != "library-home-feed") continue
            val data = q.optJSONObject("state")?.optJSONObject("data") ?: continue
            val rows = data.optJSONArray("rows")
            if (rows != null) {
                for (j in 0 until rows.length()) {
                    val row = rows.optJSONObject(j) ?: continue
                    val cards = linkedMapOf<String, SearchResponse>()
                    collect(row.opt("items"), cards, canonical = canonical)
                    if (cards.isNotEmpty()) result.getOrPut(row.optString("title").ifBlank { "DİZİSOL" }) { mutableListOf() }.addAll(cards.values)
                }
            } else {
                val cards = linkedMapOf<String, SearchResponse>()
                collect(data, cards, canonical = canonical)
                if (cards.isNotEmpty()) result["DİZİSOL"] = cards.values.toMutableList()
            }
        }
        return result.map { HomePageList(it.key, it.value.distinctBy { card -> card.url }) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = homeLists(app.get(mainUrl).text)
        val chosen = if (request.data == "home") lists else lists.filter { it.name.contains(request.data, true) }
        return newHomePageResponse(chosen, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("${mainUrl}arama?q=${URLEncoder.encode(query, "UTF-8")}").text
        val out = linkedMapOf<String, SearchResponse>()
        val doc = Jsoup.parse(html, mainUrl)
        // Search results must keep the site's canonical slug URL so "Open in browser" works.
        for (a in doc.select("a[href*=/film/], a[href*=/dizi/], a[href*=/izle/film/], a[href*=/izle/dizi/]")) {
            val href = a.absUrl("href")
            val title = a.selectFirst("h1,h2,h3,h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().trim()
            if (href.isBlank() || title.isBlank()) continue
            val tv = href.contains("/izle/dizi/")
            val image = a.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            val item = if (tv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = image?.takeIf { it.startsWith("http") }
            } else newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = image?.takeIf { it.startsWith("http") }
            }
            out[href] = item
        }
        if (out.isEmpty()) {
            ssr(html)?.optJSONObject("state")?.optJSONArray("queries")?.let { collect(it, out, canonical = canonicalMap(html)) }
        }
        return out.values.toList()
    }

    private fun findTmdbId(value: Any?): Int? {
        when (value) {
            is JSONObject -> {
                val id = value.optInt("tmdbId")
                if (id > 0) return id
                val keys = value.keys()
                while (keys.hasNext()) findTmdbId(value.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findTmdbId(value.opt(i))?.let { return it }
        }
        return null
    }

    private fun apiUrl(id: Int, type: String) = "${mainUrl}api/movies/by-tmdb/$id?type=$type"

    private fun records(raw: String): List<JSONObject> = runCatching {
        val s = raw.trim()
        if (s.startsWith("[")) {
            val a = JSONArray(s); (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        } else {
            val o = JSONObject(s)
            val a = o.optJSONArray("results") ?: o.optJSONArray("episodes")
            if (a == null) listOf(o) else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
    }.getOrDefault(emptyList())

    override suspend fun load(url: String): LoadResponse? {
        val p = url.split("|")
        val id: Int
        val type: String
        if (p.size >= 3 && p[0] == "dizisol") {
            id = p[1].toIntOrNull() ?: return null
            type = p[2]
        } else if (url.contains("/film/") || url.contains("/dizi/") || url.contains("/izle/film/") || url.contains("/dizi/") || url.contains("/izle/dizi/")) {
            type = if (url.contains("/dizi/") || url.contains("/izle/dizi/")) "tv" else "movie"
            val pageHtml = app.get(url).text
            id = findTmdbId(ssr(pageHtml)) ?: return null
        } else return null
        val items = records(app.get(apiUrl(id, type)).text)
        if (items.isEmpty()) return null
        val show = items.firstOrNull { it.optInt("season") == 0 && it.optInt("episode") == 0 } ?: items.first()
        val title = show.optString("titleTr").takeIf { it.isNotBlank() && it != "null" }
            ?: show.optString("title").substringBefore(" - ").ifBlank { "DİZİSOL" }
        val image = poster(show.optString("poster"))
        val plot = show.optString("overview").takeIf { it.isNotBlank() && it != "null" }
        val year = show.optInt("year").takeIf { it > 0 }
        if (type == "tv") {
            val episodes = items.filter { it.optInt("season") > 0 && it.optInt("episode") > 0 }
                .sortedWith(compareBy<JSONObject> { it.optInt("season") }.thenBy { it.optInt("episode") })
                .map { ep ->
                    val epId = ep.optInt("id")
                    newEpisode("dizisol-episode|$id|$epId") {
                        name = ep.optString("title").substringAfter(" - ", ep.optString("title"))
                        season = ep.optInt("season"); episode = ep.optInt("episode")
                        description = ep.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                        posterUrl = poster(ep.optString("poster"))
                    }
                }
            return newTvSeriesLoadResponse(title, "dizisol-show|$id", TvType.TvSeries, episodes) {
                posterUrl = image; this.plot = plot; this.year = year
            }
        }
        val movie = items.first()
        val movieTitle = movie.optString("titleTr").takeIf { it.isNotBlank() && it != "null" }
            ?: movie.optString("title").ifBlank { title }
        return newMovieLoadResponse(movieTitle, "dizisol-movie|$id", TvType.Movie, "dizisol-movie|$id") {
            posterUrl = poster(movie.optString("poster")) ?: image
            this.plot = movie.optString("overview").takeIf { it.isNotBlank() && it != "null" } ?: plot
            this.year = movie.optInt("year").takeIf { it > 0 } ?: year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val p = data.split("|")
        val id = p.getOrNull(1)?.toIntOrNull() ?: return false
        val type: String
        val epId: Int?
        when (p.firstOrNull()) {
            "dizisol-movie" -> { type = "movie"; epId = null }
            "dizisol-episode" -> { type = "tv"; epId = p.getOrNull(2)?.toIntOrNull() ?: return false }
            else -> return false
        }
        val items = records(app.get(apiUrl(id, type)).text)
        val detail = if (epId == null) items.firstOrNull() else items.firstOrNull { it.optInt("id") == epId }
        if (detail == null) return false
        val sources = detail.optJSONArray("sources") ?: JSONArray()
        var found = false
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (!source.optBoolean("isActive", true)) continue
            val stream = source.optString("m3u8Url")
            if (!stream.startsWith("http")) continue
            val provider = source.optString("provider").ifBlank { "Kaynak" }
            callback(newExtractorLink("DİZİSOL", provider, stream, type = ExtractorLinkType.M3U8) {
                referer = source.optString("sourceUrl").substringAfter("|", mainUrl).takeIf { it.startsWith("http") } ?: mainUrl
            })
            source.optString("subtitleTr").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("Turkish", it)) }
            source.optString("subtitleEn").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("English", it)) }
            found = true
        }
        if (!found) {
            val stream = detail.optString("m3u8Url")
            if (stream.startsWith("http")) {
                callback(newExtractorLink("DİZİSOL", "DİZİSOL", stream, type = ExtractorLinkType.M3U8) { referer = mainUrl })
                found = true
            }
            detail.optString("subtitleTr").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("Turkish", it)) }
            detail.optString("subtitleEn").takeIf { it.startsWith("http") }?.let { subtitleCallback(SubtitleFile("English", it)) }
        }
        return found
    }
}
