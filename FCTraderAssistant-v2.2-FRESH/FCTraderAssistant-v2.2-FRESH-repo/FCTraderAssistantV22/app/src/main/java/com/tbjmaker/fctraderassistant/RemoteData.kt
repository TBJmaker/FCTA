package com.tbjmaker.fctraderassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RemoteData {
    suspend fun fetch(url: String): RemoteFeed = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "The data source must use HTTPS" }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "FCTraderAssistant/2.0")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Data source returned HTTP $status")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parseFeed(text)
        } finally {
            connection.disconnect()
        }
    }

    fun parseFeed(raw: String): RemoteFeed {
        val trimmed = raw.trim()
        val root: JSONObject
        val cardsArray: JSONArray

        if (trimmed.startsWith("[")) {
            root = JSONObject()
            cardsArray = JSONArray(trimmed)
        } else {
            root = JSONObject(trimmed)
            cardsArray = root.optJSONArray("cards") ?: JSONArray()
        }

        val metaObj = root.optJSONObject("meta") ?: JSONObject()
        val meta = FeedMeta(
            version = metaObj.optInt("version", root.optInt("version", 1)),
            game = metaObj.optString("game", root.optString("game", "EA SPORTS FC 27")),
            updatedAt = metaObj.optString("updatedAt", root.optString("updatedAt", "")),
            source = metaObj.optString("source", root.optString("source", "FC Trader Assistant Cloud"))
        )

        val cards = buildList {
            for (i in 0 until cardsArray.length()) {
                val o = cardsArray.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isBlank()) continue
                val id = o.optString("id", "").trim().ifBlank {
                    slug("$name-${o.optString("version", "Base")}-${o.optInt("rating", 0)}-$i")
                }
                add(
                    CardItem(
                        id = id,
                        name = name,
                        version = o.optString("version", o.optString("cardType", "Base")),
                        rating = o.optInt("rating", 0),
                        position = o.optString("position", ""),
                        club = o.optString("club", ""),
                        league = o.optString("league", ""),
                        nation = o.optString("nation", ""),
                        pricePc = o.optInt("pricePc", o.optInt("pcPrice", o.optInt("price", 0))),
                        priceConsole = o.optInt("priceConsole", o.optInt("consolePrice", 0)),
                        trendPc = o.optDouble("trendPc", o.optDouble("pcTrend", o.optDouble("trend", 0.0))),
                        trendConsole = o.optDouble("trendConsole", o.optDouble("consoleTrend", 0.0)),
                        source = o.optString("source", meta.source),
                        updatedAt = o.optString("updatedAt", meta.updatedAt),
                        custom = false
                    )
                )
            }
        }

        if (cards.isEmpty()) error("The feed contained no player cards")
        RemoteFeed(meta = meta, cards = cards.distinctBy { it.id })
    }

    private fun slug(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}
