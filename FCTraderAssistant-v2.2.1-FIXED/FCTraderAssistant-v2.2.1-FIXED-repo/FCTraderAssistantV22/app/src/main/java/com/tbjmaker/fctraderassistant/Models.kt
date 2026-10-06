package com.tbjmaker.fctraderassistant

import kotlin.math.ceil
import kotlin.math.roundToInt

const val EA_TAX_RATE = 0.05
const val DEFAULT_REMOTE_URL = "https://raw.githubusercontent.com/TBJmaker/FC-Trader-Assistant/main/data/cards.json"
const val LIVE_DATABASE_URL = "https://www.fut.gg/players/"

enum class MarketPlatform(val label: String) {
    PC("PC"),
    CONSOLE("Console")
}

data class AppSettings(
    val platform: MarketPlatform = MarketPlatform.PC,
    val targetRoi: Double = 12.0,
    val remoteUrl: String = DEFAULT_REMOTE_URL,
    val autoSync: Boolean = true
)

data class CardItem(
    val id: String,
    val name: String,
    val version: String,
    val rating: Int,
    val position: String,
    val club: String = "",
    val league: String = "",
    val nation: String = "",
    val pricePc: Int = 0,
    val priceConsole: Int = 0,
    val trendPc: Double = 0.0,
    val trendConsole: Double = 0.0,
    val source: String = "Cloud",
    val updatedAt: String = "",
    val custom: Boolean = false
) {
    fun price(platform: MarketPlatform): Int = when (platform) {
        MarketPlatform.PC -> pricePc
        MarketPlatform.CONSOLE -> priceConsole
    }

    fun trend(platform: MarketPlatform): Double = when (platform) {
        MarketPlatform.PC -> trendPc
        MarketPlatform.CONSOLE -> trendConsole
    }
}

data class Trade(
    val id: Long,
    val cardId: String,
    val player: String,
    val version: String,
    val rating: Int,
    val position: String,
    val buyPrice: Int,
    val targetSellPrice: Int,
    val boughtAt: Long,
    val soldPrice: Int? = null,
    val soldAt: Long? = null
)

data class FeedMeta(
    val version: Int = 1,
    val game: String = "EA SPORTS FC 27",
    val updatedAt: String = "",
    val source: String = "FC Trader Assistant Cloud"
)

data class RemoteFeed(
    val meta: FeedMeta = FeedMeta(),
    val cards: List<CardItem> = emptyList()
)

data class PersonalBackup(
    val balance: Int,
    val settings: AppSettings,
    val trades: List<Trade>,
    val customCards: List<CardItem>,
    val watchlist: Set<String>,
    val localPcPrices: Map<String, Int>,
    val localConsolePrices: Map<String, Int>
)

fun netAfterTax(sellPrice: Int): Int = (sellPrice * (1.0 - EA_TAX_RATE)).roundToInt()

fun profitAfterTax(buyPrice: Int, sellPrice: Int): Int = netAfterTax(sellPrice) - buyPrice

fun roiAfterTax(buyPrice: Int, sellPrice: Int): Double {
    if (buyPrice <= 0) return 0.0
    return profitAfterTax(buyPrice, sellPrice) * 100.0 / buyPrice
}

fun breakEvenSalePrice(buyPrice: Int): Int {
    if (buyPrice <= 0) return 0
    return ceil(buyPrice / (1.0 - EA_TAX_RATE)).toInt()
}

fun targetSaleForRoi(buyPrice: Int, roiPercent: Double): Int {
    if (buyPrice <= 0) return 0
    return ceil((buyPrice * (1.0 + roiPercent / 100.0)) / (1.0 - EA_TAX_RATE)).toInt()
}

val starterCards = listOf(
    CardItem("mbappe-base", "Kylian Mbappé", "Base", 91, "ST", "Real Madrid", "LALIGA EA SPORTS", "France", 0, 0, source = "Starter"),
    CardItem("haaland-base", "Erling Haaland", "Base", 91, "ST", "Manchester City", "Premier League", "Norway", 0, 0, source = "Starter"),
    CardItem("putellas-base", "Alexia Putellas", "Base", 91, "CM", "FC Barcelona", "Liga F", "Spain", 0, 0, source = "Starter"),
    CardItem("bellingham-base", "Jude Bellingham", "Base", 90, "CAM", "Real Madrid", "LALIGA EA SPORTS", "England", 0, 0, source = "Starter"),
    CardItem("olise-base", "Michael Olise", "Base", 90, "RM", "FC Bayern München", "Bundesliga", "France", 0, 0, source = "Starter"),
    CardItem("donnarumma-base", "Gianluigi Donnarumma", "Base", 89, "GK", "Manchester City", "Premier League", "Italy", 19500, 0, 3.4, 0.0, "Starter"),
    CardItem("ekitike-base", "Hugo Ekitiké", "Base", 83, "ST", "Liverpool", "Premier League", "France", 9800, 0, 5.8, 0.0, "Starter")
)
