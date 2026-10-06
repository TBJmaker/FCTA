package com.tbjmaker.fctraderassistant

import kotlin.math.ceil
import kotlin.math.roundToInt

const val EA_TAX_RATE = 0.05
const val DEFAULT_REMOTE_URL = "https://raw.githubusercontent.com/TBJmaker/FCTA/main/data/cards.json"

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

data class NotificationPrefs(
    val enabled: Boolean = true,
    val aiIdeas: Boolean = true,
    val marketMoves: Boolean = true,
    val portfolioTargets: Boolean = true,
    val dailyBriefing: Boolean = true
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
    val notificationPrefs: NotificationPrefs,
    val trades: List<Trade>,
    val customCards: List<CardItem>,
    val watchlist: Set<String>,
    val localPcPrices: Map<String, Int>,
    val localConsolePrices: Map<String, Int>
)

enum class TradeSignal(val label: String) {
    BUY("BUY"), SELL("SELL"), HOLD("HOLD")
}

data class AiIdea(
    val card: CardItem,
    val signal: TradeSignal,
    val headline: String,
    val reason: String,
    val risk: String,
    val confidence: Int,
    val targetBuy: Int,
    val targetSell: Int,
    val potentialProfit: Int
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

fun v23SignalForTrend(trend: Double): TradeSignal = when {
    trend >= 2.5 -> TradeSignal.BUY
    trend <= -2.5 -> TradeSignal.SELL
    else -> TradeSignal.HOLD
}

fun v23RoundedMarketPrice(value: Int): Int {
    if (value <= 0) return 0
    val step = when {
        value >= 100_000 -> 1_000
        value >= 10_000 -> 500
        else -> 100
    }
    return ((value + step / 2) / step) * step
}

fun suggestedBuyPrice(current: Int, trend: Double): Int {
    if (current <= 0) return 0
    val factor = when {
        trend >= 8.0 -> 0.97
        trend >= 2.5 -> 0.96
        trend <= -8.0 -> 0.91
        trend <= -2.5 -> 0.93
        else -> 0.95
    }
    return v23RoundedMarketPrice((current * factor).toInt())
}

fun suggestedSellPrice(current: Int, trend: Double, roi: Double): Int {
    val buy = suggestedBuyPrice(current, trend)
    return if (buy > 0) v23RoundedMarketPrice(targetSaleForRoi(buy, roi)) else 0
}

fun buildAiIdeas(
    cards: List<CardItem>,
    balance: Int,
    platform: MarketPlatform,
    targetRoi: Double,
    currentPrice: (CardItem) -> Int = { it.price(platform) },
    limit: Int = 5
): List<AiIdea> {
    return cards.asSequence()
        .mapNotNull { card ->
            val price = currentPrice(card)
            val trend = card.trend(platform)
            if (price <= 0 || price > balance || trend <= 0.0) return@mapNotNull null
            val buy = suggestedBuyPrice(price, trend)
            val sell = suggestedSellPrice(price, trend, targetRoi)
            val profit = if (buy > 0 && sell > 0) profitAfterTax(buy, sell) else 0
            val risk = when {
                kotlin.math.abs(trend) >= 12.0 -> "High"
                kotlin.math.abs(trend) >= 6.0 -> "Medium"
                else -> "Lower"
            }
            val confidence = (55 + (trend.coerceIn(0.0, 15.0) * 2.4)).roundToInt().coerceIn(55, 91)
            AiIdea(
                card = card,
                signal = v23SignalForTrend(trend),
                headline = when {
                    trend >= 8.0 -> "Strong momentum"
                    trend >= 4.0 -> "Momentum opportunity"
                    else -> "Worth watching"
                },
                reason = "${if (trend >= 0) "+" else ""}${"%.1f".format(trend)}% recent move with an entry below the current market price.",
                risk = risk,
                confidence = confidence,
                targetBuy = buy,
                targetSell = sell,
                potentialProfit = profit
            )
        }
        .sortedWith(compareByDescending<AiIdea> { it.confidence }.thenByDescending { it.potentialProfit })
        .take(limit)
        .toList()
}

val starterCards = listOf(
    CardItem("mbappe-base-91", "Kylian Mbappé", "Base", 91, "ST", "Real Madrid", "LALIGA EA SPORTS", "France", 0, 0, source = "Starter"),
    CardItem("haaland-base-91", "Erling Haaland", "Base", 91, "ST", "Manchester City", "Premier League", "Norway", 0, 0, source = "Starter"),
    CardItem("putellas-base-91", "Alexia Putellas", "Base", 91, "CM", "FC Barcelona", "Liga F", "Spain", 0, 0, source = "Starter"),
    CardItem("bellingham-base-90", "Jude Bellingham", "Base", 90, "CAM", "Real Madrid", "LALIGA EA SPORTS", "England", 0, 0, source = "Starter"),
    CardItem("olise-base-90", "Michael Olise", "Base", 90, "RM", "FC Bayern München", "Bundesliga", "France", 0, 0, source = "Starter")
)
