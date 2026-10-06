package com.tbjmaker.fctraderassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "fc_trader_v2"
private const val KEY_BALANCE = "coin_balance"
private const val KEY_SETTINGS = "settings"
private const val KEY_TRADES = "trades"
private const val KEY_WATCHLIST = "watchlist"
private const val KEY_CUSTOM_CARDS = "custom_cards"
private const val KEY_REMOTE_CARDS = "remote_cards"
private const val KEY_REMOTE_META = "remote_meta"
private const val KEY_LAST_SYNC = "last_sync"
private const val KEY_PC_OVERRIDES = "pc_price_overrides"
private const val KEY_CONSOLE_OVERRIDES = "console_price_overrides"
private const val KEY_NOTIFICATION_PREFS = "notification_prefs"
private const val KEY_LAST_DAILY_BRIEFING = "last_daily_briefing"
private const val KEY_LAST_MARKET_ALERT = "last_market_alert"
private const val KEY_LAST_AI_ALERT = "last_ai_alert"
private const val KEY_LAST_PORTFOLIO_ALERT = "last_portfolio_alert"

object Storage {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadBalance(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_BALANCE)) p.getInt(KEY_BALANCE, 0) else null
    }

    fun saveBalance(context: Context, balance: Int) {
        prefs(context).edit().putInt(KEY_BALANCE, balance.coerceAtLeast(0)).apply()
    }

    fun loadSettings(context: Context): AppSettings {
        val raw = prefs(context).getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                platform = runCatching { MarketPlatform.valueOf(o.optString("platform", "PC")) }.getOrDefault(MarketPlatform.PC),
                targetRoi = o.optDouble("targetRoi", 12.0),
                remoteUrl = o.optString("remoteUrl", DEFAULT_REMOTE_URL).ifBlank { DEFAULT_REMOTE_URL },
                autoSync = o.optBoolean("autoSync", true)
            )
        }.getOrDefault(AppSettings())
    }

    fun saveSettings(context: Context, settings: AppSettings) {
        val o = JSONObject().apply {
            put("platform", settings.platform.name)
            put("targetRoi", settings.targetRoi)
            put("remoteUrl", settings.remoteUrl)
            put("autoSync", settings.autoSync)
        }
        prefs(context).edit().putString(KEY_SETTINGS, o.toString()).apply()
    }


    fun loadNotificationPrefs(context: Context): NotificationPrefs {
        val raw = prefs(context).getString(KEY_NOTIFICATION_PREFS, null) ?: return NotificationPrefs()
        return runCatching {
            val o = JSONObject(raw)
            NotificationPrefs(
                enabled = o.optBoolean("enabled", true),
                aiIdeas = o.optBoolean("aiIdeas", true),
                marketMoves = o.optBoolean("marketMoves", true),
                portfolioTargets = o.optBoolean("portfolioTargets", true),
                dailyBriefing = o.optBoolean("dailyBriefing", true)
            )
        }.getOrDefault(NotificationPrefs())
    }

    fun saveNotificationPrefs(context: Context, value: NotificationPrefs) {
        val o = JSONObject().apply {
            put("enabled", value.enabled)
            put("aiIdeas", value.aiIdeas)
            put("marketMoves", value.marketMoves)
            put("portfolioTargets", value.portfolioTargets)
            put("dailyBriefing", value.dailyBriefing)
        }
        prefs(context).edit().putString(KEY_NOTIFICATION_PREFS, o.toString()).apply()
    }

    fun getLastDailyBriefing(context: Context): Long = prefs(context).getLong(KEY_LAST_DAILY_BRIEFING, 0L)
    fun setLastDailyBriefing(context: Context, value: Long) { prefs(context).edit().putLong(KEY_LAST_DAILY_BRIEFING, value).apply() }

    fun getLastMarketAlert(context: Context): String = prefs(context).getString(KEY_LAST_MARKET_ALERT, "") ?: ""
    fun setLastMarketAlert(context: Context, value: String) { prefs(context).edit().putString(KEY_LAST_MARKET_ALERT, value).apply() }

    fun getLastAiAlert(context: Context): String = prefs(context).getString(KEY_LAST_AI_ALERT, "") ?: ""
    fun setLastAiAlert(context: Context, value: String) { prefs(context).edit().putString(KEY_LAST_AI_ALERT, value).apply() }

    fun getLastPortfolioAlert(context: Context): String = prefs(context).getString(KEY_LAST_PORTFOLIO_ALERT, "") ?: ""
    fun setLastPortfolioAlert(context: Context, value: String) { prefs(context).edit().putString(KEY_LAST_PORTFOLIO_ALERT, value).apply() }

    fun loadTrades(context: Context): List<Trade> = decodeTrades(prefs(context).getString(KEY_TRADES, null))

    fun saveTrades(context: Context, trades: List<Trade>) {
        prefs(context).edit().putString(KEY_TRADES, encodeTrades(trades).toString()).apply()
    }

    fun loadWatchlist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WATCHLIST, emptySet())?.toSet() ?: emptySet()

    fun saveWatchlist(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_WATCHLIST, ids).apply()
    }

    fun loadCustomCards(context: Context): List<CardItem> =
        decodeCards(prefs(context).getString(KEY_CUSTOM_CARDS, null), custom = true)

    fun saveCustomCards(context: Context, cards: List<CardItem>) {
        prefs(context).edit().putString(KEY_CUSTOM_CARDS, encodeCards(cards).toString()).apply()
    }

    fun loadRemoteCards(context: Context): List<CardItem> =
        decodeCards(prefs(context).getString(KEY_REMOTE_CARDS, null), custom = false)

    fun saveRemoteFeed(context: Context, feed: RemoteFeed) {
        val meta = JSONObject().apply {
            put("version", feed.meta.version)
            put("game", feed.meta.game)
            put("updatedAt", feed.meta.updatedAt)
            put("source", feed.meta.source)
        }
        prefs(context).edit()
            .putString(KEY_REMOTE_CARDS, encodeCards(feed.cards).toString())
            .putString(KEY_REMOTE_META, meta.toString())
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    fun loadFeedMeta(context: Context): FeedMeta {
        val raw = prefs(context).getString(KEY_REMOTE_META, null) ?: return FeedMeta()
        return runCatching {
            val o = JSONObject(raw)
            FeedMeta(
                version = o.optInt("version", 1),
                game = o.optString("game", "EA SPORTS FC 27"),
                updatedAt = o.optString("updatedAt", ""),
                source = o.optString("source", "FC Trader Assistant Cloud")
            )
        }.getOrDefault(FeedMeta())
    }

    fun loadLastSync(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun loadPriceOverrides(context: Context, platform: MarketPlatform): Map<String, Int> {
        val key = if (platform == MarketPlatform.PC) KEY_PC_OVERRIDES else KEY_CONSOLE_OVERRIDES
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { id -> put(id, o.optInt(id, 0)) }
            }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    fun savePriceOverrides(context: Context, platform: MarketPlatform, overrides: Map<String, Int>) {
        val key = if (platform == MarketPlatform.PC) KEY_PC_OVERRIDES else KEY_CONSOLE_OVERRIDES
        val o = JSONObject()
        overrides.forEach { (id, value) -> if (value > 0) o.put(id, value) }
        prefs(context).edit().putString(key, o.toString()).apply()
    }

    fun exportBackup(context: Context): String {
        val balance = loadBalance(context) ?: 0
        val settings = loadSettings(context)
        val root = JSONObject().apply {
            put("format", "fc-trader-assistant-backup")
            put("version", 2)
            put("balance", balance)
            put("settings", JSONObject().apply {
                put("platform", settings.platform.name)
                put("targetRoi", settings.targetRoi)
                put("remoteUrl", settings.remoteUrl)
                put("autoSync", settings.autoSync)
            })
            val np = loadNotificationPrefs(context)
            put("notificationPrefs", JSONObject().apply {
                put("enabled", np.enabled)
                put("aiIdeas", np.aiIdeas)
                put("marketMoves", np.marketMoves)
                put("portfolioTargets", np.portfolioTargets)
                put("dailyBriefing", np.dailyBriefing)
            })
            put("trades", encodeTrades(loadTrades(context)))
            put("customCards", encodeCards(loadCustomCards(context)))
            put("watchlist", JSONArray(loadWatchlist(context).toList()))
            put("pcOverrides", JSONObject(loadPriceOverrides(context, MarketPlatform.PC)))
            put("consoleOverrides", JSONObject(loadPriceOverrides(context, MarketPlatform.CONSOLE)))
        }
        return root.toString(2)
    }

    fun importBackup(context: Context, raw: String): Result<Unit> = runCatching {
        val root = JSONObject(raw)
        require(root.optString("format") == "fc-trader-assistant-backup") { "Not an FC Trader Assistant backup" }

        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val settings = AppSettings(
            platform = runCatching { MarketPlatform.valueOf(settingsObj.optString("platform", "PC")) }.getOrDefault(MarketPlatform.PC),
            targetRoi = settingsObj.optDouble("targetRoi", 12.0),
            remoteUrl = settingsObj.optString("remoteUrl", DEFAULT_REMOTE_URL).ifBlank { DEFAULT_REMOTE_URL },
            autoSync = settingsObj.optBoolean("autoSync", true)
        )

        val editor = prefs(context).edit()
        editor.putInt(KEY_BALANCE, root.optInt("balance", 0).coerceAtLeast(0))
        editor.putString(KEY_SETTINGS, JSONObject().apply {
            put("platform", settings.platform.name)
            put("targetRoi", settings.targetRoi)
            put("remoteUrl", settings.remoteUrl)
            put("autoSync", settings.autoSync)
        }.toString())
        editor.putString(KEY_TRADES, (root.optJSONArray("trades") ?: JSONArray()).toString())
        editor.putString(KEY_CUSTOM_CARDS, (root.optJSONArray("customCards") ?: JSONArray()).toString())

        val watchArray = root.optJSONArray("watchlist") ?: JSONArray()
        val watch = buildSet {
            for (i in 0 until watchArray.length()) add(watchArray.optString(i))
        }
        editor.putStringSet(KEY_WATCHLIST, watch)
        editor.putString(KEY_PC_OVERRIDES, (root.optJSONObject("pcOverrides") ?: JSONObject()).toString())
        editor.putString(KEY_CONSOLE_OVERRIDES, (root.optJSONObject("consoleOverrides") ?: JSONObject()).toString())
        val n = root.optJSONObject("notificationPrefs")
        if (n != null) editor.putString(KEY_NOTIFICATION_PREFS, n.toString())
        editor.apply()
    }

    private fun encodeCards(cards: List<CardItem>): JSONArray = JSONArray().apply {
        cards.forEach { c ->
            put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("version", c.version)
                put("rating", c.rating)
                put("position", c.position)
                put("club", c.club)
                put("league", c.league)
                put("nation", c.nation)
                put("pricePc", c.pricePc)
                put("priceConsole", c.priceConsole)
                put("trendPc", c.trendPc)
                put("trendConsole", c.trendConsole)
                put("source", c.source)
                put("updatedAt", c.updatedAt)
                put("custom", c.custom)
            })
        }
    }

    private fun decodeCards(raw: String?, custom: Boolean): List<CardItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        CardItem(
                            id = o.optString("id", "card-$i"),
                            name = o.optString("name", "Unknown"),
                            version = o.optString("version", "Base"),
                            rating = o.optInt("rating", 0),
                            position = o.optString("position", ""),
                            club = o.optString("club", ""),
                            league = o.optString("league", ""),
                            nation = o.optString("nation", ""),
                            pricePc = o.optInt("pricePc", o.optInt("price", 0)),
                            priceConsole = o.optInt("priceConsole", 0),
                            trendPc = o.optDouble("trendPc", o.optDouble("trend", 0.0)),
                            trendConsole = o.optDouble("trendConsole", 0.0),
                            source = o.optString("source", if (custom) "Manual" else "Cloud"),
                            updatedAt = o.optString("updatedAt", ""),
                            custom = custom || o.optBoolean("custom", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeTrades(trades: List<Trade>): JSONArray = JSONArray().apply {
        trades.forEach { t ->
            put(JSONObject().apply {
                put("id", t.id)
                put("cardId", t.cardId)
                put("player", t.player)
                put("version", t.version)
                put("rating", t.rating)
                put("position", t.position)
                put("buyPrice", t.buyPrice)
                put("targetSellPrice", t.targetSellPrice)
                put("boughtAt", t.boughtAt)
                if (t.soldPrice == null) put("soldPrice", JSONObject.NULL) else put("soldPrice", t.soldPrice)
                if (t.soldAt == null) put("soldAt", JSONObject.NULL) else put("soldAt", t.soldAt)
            })
        }
    }

    private fun decodeTrades(raw: String?): List<Trade> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Trade(
                            id = o.optLong("id", System.currentTimeMillis() + i),
                            cardId = o.optString("cardId", ""),
                            player = o.optString("player", "Unknown"),
                            version = o.optString("version", "Base"),
                            rating = o.optInt("rating", 0),
                            position = o.optString("position", ""),
                            buyPrice = o.optInt("buyPrice", o.optInt("buy", 0)),
                            targetSellPrice = o.optInt("targetSellPrice", o.optInt("expectedSell", 0)),
                            boughtAt = o.optLong("boughtAt", o.optLong("id", System.currentTimeMillis())),
                            soldPrice = if (o.isNull("soldPrice")) null else o.optInt("soldPrice"),
                            soldAt = if (o.isNull("soldAt")) null else o.optLong("soldAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
