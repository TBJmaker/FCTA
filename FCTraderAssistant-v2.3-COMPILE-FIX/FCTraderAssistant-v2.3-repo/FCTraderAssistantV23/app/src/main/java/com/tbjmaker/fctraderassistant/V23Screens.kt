package com.tbjmaker.fctraderassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue

private val V23Muted = Color(0xFF93A4B8)
private val V23Danger = Color(0xFFFF6B6B)
private val V23Hold = Color(0xFFFFC857)
private val V23Panel = Color(0xFF0C1A28)

private fun v23Coins(value: Int): String = "%,d".format(value)
private fun v23Pct(value: Double): String = "${if (value > 0) "+" else ""}${"%.1f".format(value)}%"
private fun v23DateTime(value: Long): String = if (value <= 0L) "Not synced yet" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

private fun v23Matches(card: CardItem, q: String): Boolean {
    if (q.isBlank()) return true
    val needle = q.trim()
    return listOf(
        card.name,
        card.club,
        card.league,
        card.nation,
        card.position,
        card.version,
        card.rating.toString()
    ).any { it.contains(needle, ignoreCase = true) }
}

private fun v23FilterCards(
    cards: List<CardItem>,
    query: String,
    club: String,
    league: String,
    nation: String,
    position: String,
    version: String,
    minRating: Int?,
    maxPrice: Int?,
    currentPrice: (CardItem) -> Int
): List<CardItem> {
    return cards.filter { card ->
        v23Matches(card, query) &&
            (club.isBlank() || card.club.contains(club, true)) &&
            (league.isBlank() || card.league.contains(league, true)) &&
            (nation.isBlank() || card.nation.contains(nation, true)) &&
            (position.isBlank() || card.position.contains(position, true)) &&
            (version.isBlank() || card.version.contains(version, true)) &&
            (minRating == null || card.rating >= minRating) &&
            (maxPrice == null || currentPrice(card) in 1..maxPrice)
    }
}

@Composable
private fun V23Header(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = V23Muted, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
private fun V23Metric(title: String, value: String, subtitle: String = "", valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = V23Muted, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = valueColor)
            if (subtitle.isNotBlank()) Text(subtitle, color = V23Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun V23SignalBadge(signal: TradeSignal, accent: Color) {
    val color = when (signal) {
        TradeSignal.BUY -> accent
        TradeSignal.SELL -> V23Danger
        TradeSignal.HOLD -> V23Hold
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(signal.label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun V23SearchSuggestion(
    card: CardItem,
    price: Int,
    platform: MarketPlatform,
    accent: Color,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.name, fontWeight = FontWeight.SemiBold)
                Text("${card.rating} ${card.position} · ${card.version}", color = V23Muted, fontSize = 12.sp)
                val meta = listOf(card.club, card.league).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = V23Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (price > 0) v23Coins(price) else "—", fontWeight = FontWeight.SemiBold)
                val trend = card.trend(platform)
                if (trend != 0.0) Text(v23Pct(trend), color = if (trend > 0) accent else V23Danger, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun V23HomeScreen(
    padding: PaddingValues,
    balance: Int,
    trades: List<Trade>,
    cards: List<CardItem>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    targetRoi: Double,
    lastSync: Long,
    syncStatus: SyncStatus,
    accent: Color,
    onChangeBalance: () -> Unit,
    onSync: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenCard: (CardItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val open = trades.filter { it.soldPrice == null }
    val sold = trades.filter { it.soldPrice != null }
    val realised = sold.sumOf { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
    val ideas = remember(cards, balance, platform, targetRoi) { buildAiIdeas(cards, balance, platform, targetRoi, currentPrice, 3) }
    val suggestions = remember(cards, query) {
        if (query.trim().length < 2) emptyList() else cards.filter { v23Matches(it, query) }.sortedWith(compareByDescending<CardItem> { it.name.startsWith(query, true) }.thenByDescending { it.rating }).take(6)
    }

    LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 26.dp)) {
        item {
            V23Header("FC Trader Assistant", "${platform.label} market · Updated ${v23DateTime(lastSync)}") {
                IconButton(onClick = onSync) {
                    if (syncStatus is SyncStatus.Syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Refresh prices")
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V23Metric("Available coins", v23Coins(balance), "Tap balance if you trade outside the app")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onChangeBalance, modifier = Modifier.weight(1f)) { Text("Balance") }
                    Button(onClick = onOpenMarket, modifier = Modifier.weight(1f)) { Text("Market") }
                }
            }
        }
        item {
            Text("Quick player search", Modifier.padding(20.dp, 22.dp, 20.dp, 8.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    placeholder = { Text("Name, team, league, position, promo…") },
                    shape = RoundedCornerShape(18.dp)
                )
                suggestions.forEach { card -> V23SearchSuggestion(card, currentPrice(card), platform, accent) { onOpenCard(card) } }
            }
        }
        item {
            Row(Modifier.padding(20.dp, 22.dp, 20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Trader AI briefing", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenAi) { Text("Open AI") }
            }
        }
        if (ideas.isEmpty()) {
            item { Text("Sync priced market data to unlock personalised trade ideas.", Modifier.padding(horizontal = 20.dp), color = V23Muted) }
        } else {
            items(ideas, key = { "home-ai-${it.card.id}" }) { idea ->
                Card(onClick = { onOpenCard(idea.card) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(idea.card.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            V23SignalBadge(idea.signal, accent)
                        }
                        Text("${idea.headline} · ${idea.confidence}% confidence · ${idea.risk} risk", color = V23Muted, fontSize = 12.sp)
                        Text("Aim ${v23Coins(idea.targetBuy)} → ${v23Coins(idea.targetSell)} · est. +${v23Coins(idea.potentialProfit)}", fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Text("Portfolio snapshot", Modifier.padding(20.dp, 22.dp, 20.dp, 8.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V23Metric("Open positions", open.size.toString(), "${v23Coins(open.sumOf { it.buyPrice })} coins invested")
                V23Metric("Realised profit", "${if (realised >= 0) "+" else ""}${v23Coins(realised)}", "After EA's 5% tax", if (realised >= 0) accent else V23Danger)
            }
        }
    }
}

private enum class V23MarketBucket(val label: String) {
    RISERS("Risers"), FALLERS("Fallers"), BEST_BUYS("Best buys"), BEST_SELLS("Best sells")
}

@Composable
fun V23MarketScreen(
    padding: PaddingValues,
    cards: List<CardItem>,
    watchlist: Set<String>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    targetRoi: Double,
    lastSync: Long,
    accent: Color,
    onToggleWatch: (String) -> Unit,
    onOpenCard: (CardItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }
    var club by remember { mutableStateOf("") }
    var league by remember { mutableStateOf("") }
    var nation by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var minRatingText by remember { mutableStateOf("") }
    var maxPriceText by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf(V23MarketBucket.RISERS) }

    val filtered = remember(cards, query, club, league, nation, position, version, minRatingText, maxPriceText) {
        v23FilterCards(cards, query, club, league, nation, position, version, minRatingText.toIntOrNull(), maxPriceText.toIntOrNull(), currentPrice)
    }
    val priced = remember(filtered, platform) { filtered.filter { currentPrice(it) > 0 } }
    val risers = remember(priced, platform) { priced.filter { it.trend(platform) > 0.0 }.sortedByDescending { it.trend(platform) }.take(10) }
    val fallers = remember(priced, platform) { priced.filter { it.trend(platform) < 0.0 }.sortedBy { it.trend(platform) }.take(10) }
    val bestBuys = remember(priced, platform) {
        priced.filter { v23SignalForTrend(it.trend(platform)) == TradeSignal.BUY }
            .sortedWith(compareByDescending<CardItem> { it.trend(platform) }.thenBy { currentPrice(it) })
            .take(10)
    }
    val bestSells = remember(priced, platform) {
        priced.filter { v23SignalForTrend(it.trend(platform)) == TradeSignal.SELL }
            .sortedBy { it.trend(platform) }
            .take(10)
    }
    val list = when (bucket) {
        V23MarketBucket.RISERS -> risers
        V23MarketBucket.FALLERS -> fallers
        V23MarketBucket.BEST_BUYS -> bestBuys
        V23MarketBucket.BEST_SELLS -> bestSells
    }

    LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { V23Header("Market", "${cards.size} cards loaded · ${platform.label} · Updated ${v23DateTime(lastSync)}") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    placeholder = { Text("Search player, club, league, nation, position…") },
                    shape = RoundedCornerShape(18.dp)
                )
                TextButton(onClick = { filtersOpen = !filtersOpen }) {
                    Icon(if (filtersOpen) Icons.Default.ExpandLess else Icons.Default.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (filtersOpen) "Hide filters" else "More filters")
                }
                if (filtersOpen) {
                    Card(colors = CardDefaults.cardColors(containerColor = V23Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(club, { club = it }, label = { Text("Team") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(league, { league = it }, label = { Text("League") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(nation, { nation = it }, label = { Text("Nation") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(position, { position = it.uppercase() }, label = { Text("Position") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(version, { version = it }, label = { Text("Card / promo") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(minRatingText, { minRatingText = it.filter(Char::isDigit).take(2) }, label = { Text("Min rating") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(maxPriceText, { maxPriceText = it.filter(Char::isDigit).take(9) }, label = { Text("Max price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { club = ""; league = ""; nation = ""; position = ""; version = ""; minRatingText = ""; maxPriceText = "" }) { Text("Clear filters") }
                        }
                    }
                }
            }
        }

        if (query.isNotBlank() || listOf(club, league, nation, position, version, minRatingText, maxPriceText).any { it.isNotBlank() }) {
            item { Text("Search results (${filtered.size})", Modifier.padding(20.dp, 20.dp, 20.dp, 6.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            items(filtered.take(50), key = { "search-${it.id}" }) { card ->
                V23MarketRow(card, currentPrice(card), card.trend(platform), targetRoi, card.id in watchlist, accent, { onToggleWatch(card.id) }, { onOpenCard(card) })
            }
            if (filtered.size > 50) item { Text("Showing first 50 matches. Refine your filters to narrow the full database.", Modifier.padding(20.dp), color = V23Muted, fontSize = 12.sp) }
        } else {
            item {
                Text("Top 10 market areas", Modifier.padding(20.dp, 22.dp, 20.dp, 8.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        V23MarketBucket.entries.take(2).forEach { item -> FilterChip(selected = bucket == item, onClick = { bucket = item }, label = { Text(item.label) }, modifier = Modifier.weight(1f)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        V23MarketBucket.entries.drop(2).forEach { item -> FilterChip(selected = bucket == item, onClick = { bucket = item }, label = { Text(item.label) }, modifier = Modifier.weight(1f)) }
                    }
                }
            }
            item {
                val subtitle = when (bucket) {
                    V23MarketBucket.RISERS -> "10 largest positive moves across the priced database"
                    V23MarketBucket.FALLERS -> "10 largest negative moves across the priced database"
                    V23MarketBucket.BEST_BUYS -> "10 strongest BUY setups from current price/trend data"
                    V23MarketBucket.BEST_SELLS -> "10 strongest SELL setups from current price/trend data"
                }
                Text(subtitle, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = V23Muted, fontSize = 12.sp)
            }
            if (list.isEmpty()) {
                item { Text("No cards currently have enough valid price movement data for this list.", Modifier.padding(20.dp), color = V23Muted) }
            }
            items(list, key = { "rank-${bucket.name}-${it.id}" }) { card ->
                V23MarketRow(card, currentPrice(card), card.trend(platform), targetRoi, card.id in watchlist, accent, { onToggleWatch(card.id) }, { onOpenCard(card) })
            }
        }
    }
}

@Composable
private fun V23MarketRow(
    card: CardItem,
    price: Int,
    trend: Double,
    targetRoi: Double,
    watched: Boolean,
    accent: Color,
    onWatch: () -> Unit,
    onOpen: () -> Unit
) {
    val signal = v23SignalForTrend(trend)
    val buy = suggestedBuyPrice(price, trend)
    val sell = suggestedSellPrice(price, trend, targetRoi)
    Card(onClick = onOpen, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${card.rating} ${card.position} · ${card.version}", color = V23Muted, fontSize = 12.sp)
                }
                V23SignalBadge(signal, accent)
                IconButton(onClick = onWatch) { Icon(if (watched) Icons.Default.Star else Icons.Default.StarBorder, "Watch", tint = if (watched) V23Hold else LocalContentColor.current) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (price > 0) "${v23Coins(price)} coins" else "No price", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (trend != 0.0) Text(v23Pct(trend), color = if (trend > 0) accent else V23Danger, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aim buy ${if (buy > 0) v23Coins(buy) else "—"}", color = V23Muted, fontSize = 12.sp)
                Text("Aim sell ${if (sell > 0) v23Coins(sell) else "—"}", color = V23Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun V23PortfolioScreen(
    padding: PaddingValues,
    balance: Int,
    trades: List<Trade>,
    cards: List<CardItem>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    accent: Color,
    onSell: (Trade, Int) -> Unit,
    onCancelTrade: (Trade) -> Unit,
    onBuyCard: (CardItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSold by remember { mutableStateOf(false) }
    var sellTrade by remember { mutableStateOf<Trade?>(null) }
    val suggestions = remember(cards, query) {
        if (query.trim().length < 2) emptyList() else cards.filter { v23Matches(it, query) }
            .sortedWith(compareByDescending<CardItem> { it.name.startsWith(query, true) }.thenByDescending { it.rating })
            .take(10)
    }
    val list = if (showSold) trades.filter { it.soldPrice != null } else trades.filter { it.soldPrice == null }
    val cardMap = remember(cards) { cards.associateBy { it.id } }

    LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 26.dp)) {
        item { V23Header("Portfolio", "Search the same master card database used by Market") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                V23Metric("Available coins", v23Coins(balance), "${platform.label} market")
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Name, team, league, position, nation, promo…") },
                    shape = RoundedCornerShape(18.dp)
                )
                suggestions.forEach { card -> V23SearchSuggestion(card, currentPrice(card), platform, accent) { query = ""; onBuyCard(card) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showSold, onClick = { showSold = false }, label = { Text("Open (${trades.count { it.soldPrice == null }})") })
                    FilterChip(selected = showSold, onClick = { showSold = true }, label = { Text("Sold (${trades.count { it.soldPrice != null }})") })
                }
            }
        }
        if (list.isEmpty()) item { Text(if (showSold) "No completed trades yet." else "No open positions yet.", Modifier.padding(20.dp), color = V23Muted) }
        items(list.sortedByDescending { it.boughtAt }, key = { "v23-trade-${it.id}" }) { trade ->
            val current = cardMap[trade.cardId]?.let(currentPrice) ?: 0
            val reference = trade.soldPrice ?: current.takeIf { it > 0 } ?: trade.targetSellPrice
            val profit = profitAfterTax(trade.buyPrice, reference)
            Card(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(trade.player, fontWeight = FontWeight.Bold)
                            Text("${trade.rating} ${trade.position} · ${trade.version}", color = V23Muted, fontSize = 12.sp)
                        }
                        Text(if (trade.soldPrice == null) "OPEN" else "SOLD", color = if (trade.soldPrice == null) V23Muted else accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text("Bought ${v23Coins(trade.buyPrice)} · Break-even ${v23Coins(breakEvenSalePrice(trade.buyPrice))}", color = V23Muted, fontSize = 12.sp)
                    if (trade.soldPrice == null) Text("Current ${if (current > 0) v23Coins(current) else "—"} · Target ${v23Coins(trade.targetSellPrice)}", color = V23Muted, fontSize = 12.sp)
                    else Text("Sold ${v23Coins(trade.soldPrice)} · Net ${v23Coins(netAfterTax(trade.soldPrice))}", color = V23Muted, fontSize = 12.sp)
                    Text("${if (profit >= 0) "+" else ""}${v23Coins(profit)} coins", color = if (profit >= 0) accent else V23Danger, fontWeight = FontWeight.Bold)
                    if (trade.soldPrice == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { sellTrade = trade }, modifier = Modifier.weight(1f)) { Text("Mark sold") }
                            OutlinedButton(onClick = { onCancelTrade(trade) }, modifier = Modifier.weight(1f)) { Text("Undo buy") }
                        }
                    }
                }
            }
        }
    }
    sellTrade?.let { trade ->
        SellDialog(trade, onDismiss = { sellTrade = null }) { price -> onSell(trade, price); sellTrade = null }
    }
}

@Composable
fun TraderAiScreen(
    padding: PaddingValues,
    balance: Int,
    cards: List<CardItem>,
    trades: List<Trade>,
    platform: MarketPlatform,
    targetRoi: Double,
    currentPrice: (CardItem) -> Int,
    accent: Color,
    onOpenCard: (CardItem) -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    val ideas = remember(cards, balance, platform, targetRoi) { buildAiIdeas(cards, balance, platform, targetRoi, currentPrice, 5) }
    val openTrades = trades.filter { it.soldPrice == null }

    fun answerPrompt(text: String): String {
        val q = text.lowercase()
        if (cards.none { currentPrice(it) > 0 }) return "I need synced market prices before I can give a grounded answer. Refresh the market feed first."
        if ("sell" in q || "portfolio" in q) {
            val byId = cards.associateBy { it.id }
            val best = openTrades.mapNotNull { t ->
                val p = byId[t.cardId]?.let(currentPrice) ?: 0
                if (p <= 0) null else Triple(t, p, roiAfterTax(t.buyPrice, p))
            }.maxByOrNull { it.third }
            return if (best == null) "You do not have an open position with a live price yet." else "Your strongest sell candidate is ${best.first.player}. At about ${v23Coins(best.second)} coins it is roughly ${"%.1f".format(best.third)}% ROI after tax. Your target is ${v23Coins(best.first.targetSellPrice)}."
        }
        if ("drop" in q || "fall" in q || "rebound" in q) {
            val faller = cards.filter { currentPrice(it) > 0 && it.trend(platform) < 0 }.minByOrNull { it.trend(platform) }
            return if (faller == null) "I cannot see a priced faller in the current feed." else "${faller.name} ${faller.version} is the sharpest faller I can see at ${v23Pct(faller.trend(platform))}. I would watch rather than chase it until the drop slows. Current price is about ${v23Coins(currentPrice(faller))}."
        }
        val idea = ideas.firstOrNull()
        return if (idea == null) "I cannot find a priced BUY setup within your current ${v23Coins(balance)} coin balance." else "My first idea is ${idea.card.name} ${idea.card.version}. Aim around ${v23Coins(idea.targetBuy)}, target about ${v23Coins(idea.targetSell)}, estimated +${v23Coins(idea.potentialProfit)} after tax. ${idea.reason} ${idea.risk} risk."
    }

    LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { V23Header("Trader AI", "Grounded in your balance, portfolio and synced market data") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today's ideas", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("These are analysis ideas, not guaranteed outcomes. Prices and tax maths come from the synced feed.", color = V23Muted, fontSize = 12.sp)
            }
        }
        if (ideas.isEmpty()) {
            item { Text("No priced opportunities fit your balance yet. Refresh the market or change your balance.", Modifier.padding(20.dp), color = V23Muted) }
        }
        items(ideas, key = { "ai-${it.card.id}" }) { idea ->
            Card(onClick = { onOpenCard(idea.card) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = V23Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(idea.card.name, fontWeight = FontWeight.Bold)
                            Text("${idea.card.rating} ${idea.card.position} · ${idea.card.version}", color = V23Muted, fontSize = 12.sp)
                        }
                        V23SignalBadge(idea.signal, accent)
                    }
                    Text("${idea.headline} · ${idea.confidence}% confidence · ${idea.risk} risk", color = V23Muted, fontSize = 12.sp)
                    Text(idea.reason, fontSize = 12.sp)
                    Text("Aim buy ${v23Coins(idea.targetBuy)} · Aim sell ${v23Coins(idea.targetSell)} · Est. +${v23Coins(idea.potentialProfit)}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask Trader AI", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text("e.g. Best buy under my balance? What should I sell? Who is dropping hard?") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { prompt = "Best buy under my balance" }, label = { Text("Best buy") })
                    AssistChip(onClick = { prompt = "What should I sell?" }, label = { Text("Sell idea") })
                    AssistChip(onClick = { prompt = "Who is dropping hard?" }, label = { Text("Drops") })
                }
                Button(onClick = { answer = answerPrompt(prompt); if (prompt.isBlank()) prompt = "Best buy under my balance" }, modifier = Modifier.fillMaxWidth()) { Text("Analyse") }
                if (answer.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = V23Panel), shape = RoundedCornerShape(16.dp)) {
                        Text(answer, Modifier.padding(14.dp), lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

private enum class MoreSection { SETTINGS, WATCHLIST, ANALYTICS }

@Composable
fun V23MoreScreen(
    padding: PaddingValues,
    balance: Int,
    settings: AppSettings,
    notificationPrefs: NotificationPrefs,
    cards: List<CardItem>,
    watchlist: Set<String>,
    trades: List<Trade>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    targetRoi: Double,
    lastSync: Long,
    syncStatus: SyncStatus,
    accent: Color,
    onChangeBalance: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onUpdateNotifications: (NotificationPrefs) -> Unit,
    onSync: () -> Unit,
    onToggleWatch: (String) -> Unit,
    onBuy: (CardItem) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: (String) -> Unit
) {
    var section by remember { mutableStateOf(MoreSection.SETTINGS) }
    var roiText by remember(settings.targetRoi) { mutableStateOf(settings.targetRoi.toString().removeSuffix(".0")) }
    var remoteUrl by remember(settings.remoteUrl) { mutableStateOf(settings.remoteUrl) }
    var showImport by remember { mutableStateOf(false) }

    when (section) {
        MoreSection.WATCHLIST -> {
            Column(Modifier.padding(padding)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { section = MoreSection.SETTINGS }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text("Watchlist", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    val watched = cards.filter { it.id in watchlist }.sortedByDescending { it.trend(platform) }
                    if (watched.isEmpty()) item { Text("Your watchlist is empty.", Modifier.padding(20.dp), color = V23Muted) }
                    items(watched, key = { "watch-${it.id}" }) { card ->
                        V23MarketRow(card, currentPrice(card), card.trend(platform), targetRoi, true, accent, { onToggleWatch(card.id) }, { onBuy(card) })
                    }
                }
            }
            return
        }
        MoreSection.ANALYTICS -> {
            Column(Modifier.padding(padding)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { section = MoreSection.SETTINGS }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text("Analytics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                val sold = trades.filter { it.soldPrice != null }
                val realised = sold.sumOf { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
                val wins = sold.count { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) > 0 }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { V23Metric("Current balance", v23Coins(balance)) }
                    item { V23Metric("Realised profit", "${if (realised >= 0) "+" else ""}${v23Coins(realised)}", "After EA tax", if (realised >= 0) accent else V23Danger) }
                    item { V23Metric("Completed trades", sold.size.toString(), "$wins wins · ${sold.size - wins} non-wins") }
                    item {
                        val best = sold.maxByOrNull { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
                        if (best != null) V23Metric("Best trade", best.player, "+${v23Coins(profitAfterTax(best.buyPrice, best.soldPrice ?: 0).coerceAtLeast(0))} coins", accent)
                    }
                }
            }
            return
        }
        else -> Unit
    }

    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 26.dp)) {
        V23Header("More", "Settings, alerts, watchlist and analytics")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { section = MoreSection.WATCHLIST }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Star, null); Spacer(Modifier.width(6.dp)); Text("Watchlist") }
                OutlinedButton(onClick = { section = MoreSection.ANALYTICS }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Analytics, null); Spacer(Modifier.width(6.dp)); Text("Analytics") }
            }
            V23Metric("Coin balance", v23Coins(balance), "Manual correction remains available")
            OutlinedButton(onClick = onChangeBalance, modifier = Modifier.fillMaxWidth()) { Text("Change balance") }

            Text("Market", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.platform == MarketPlatform.PC, onClick = { onUpdateSettings(settings.copy(platform = MarketPlatform.PC)) }, label = { Text("PC") })
                FilterChip(selected = settings.platform == MarketPlatform.CONSOLE, onClick = { onUpdateSettings(settings.copy(platform = MarketPlatform.CONSOLE)) }, label = { Text("Console") })
            }
            OutlinedTextField(roiText, { roiText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) }, label = { Text("Default target ROI %") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onUpdateSettings(settings.copy(targetRoi = roiText.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: settings.targetRoi)) }, modifier = Modifier.fillMaxWidth()) { Text("Save ROI") }

            HorizontalDivider()
            Text("Live card & price feed", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Last updated ${v23DateTime(lastSync)}", color = V23Muted, fontSize = 12.sp)
            if (syncStatus is SyncStatus.Error) Text("Latest refresh failed; cached prices are still being used.", color = V23Danger, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-refresh on app open", Modifier.weight(1f))
                Switch(settings.autoSync, { onUpdateSettings(settings.copy(autoSync = it)) })
            }
            OutlinedTextField(remoteUrl, { remoteUrl = it.trim() }, label = { Text("JSON feed URL") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onUpdateSettings(settings.copy(remoteUrl = remoteUrl.ifBlank { DEFAULT_REMOTE_URL })) }, modifier = Modifier.weight(1f)) { Text("Save source") }
                OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f)) { Text("Refresh now") }
            }

            HorizontalDivider()
            Text("Notifications", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Background checks run about hourly when Android allows it. Alerts only use synced market data; they do not log in to EA or place trades.", color = V23Muted, fontSize = 12.sp)
            V23Toggle("Allow FC Trader notifications", notificationPrefs.enabled) { onUpdateNotifications(notificationPrefs.copy(enabled = it)) }
            V23Toggle("Trader AI opportunities", notificationPrefs.aiIdeas, notificationPrefs.enabled) { onUpdateNotifications(notificationPrefs.copy(aiIdeas = it)) }
            V23Toggle("Big market moves", notificationPrefs.marketMoves, notificationPrefs.enabled) { onUpdateNotifications(notificationPrefs.copy(marketMoves = it)) }
            V23Toggle("Portfolio target reached", notificationPrefs.portfolioTargets, notificationPrefs.enabled) { onUpdateNotifications(notificationPrefs.copy(portfolioTargets = it)) }
            V23Toggle("Daily AI briefing", notificationPrefs.dailyBriefing, notificationPrefs.enabled) { onUpdateNotifications(notificationPrefs.copy(dailyBriefing = it)) }

            HorizontalDivider()
            Text("Backup", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("Copy backup") }
                OutlinedButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) { Text("Restore") }
            }
        }
    }

    if (showImport) ImportBackupDialog(onDismiss = { showImport = false }) { raw -> onImportBackup(raw); showImport = false }
}

@Composable
private fun V23Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) LocalContentColor.current else V23Muted)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun V23PlayerDetailDialog(
    card: CardItem,
    price: Int,
    platform: MarketPlatform,
    targetRoi: Double,
    watched: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    onToggleWatch: () -> Unit,
    onBuy: () -> Unit
) {
    val trend = card.trend(platform)
    val signal = v23SignalForTrend(trend)
    val buy = suggestedBuyPrice(price, trend)
    val sell = suggestedSellPrice(price, trend, targetRoi)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${card.rating} ${card.position} · ${card.version}", color = V23Muted, modifier = Modifier.weight(1f))
                    V23SignalBadge(signal, accent)
                }
                val meta = listOf(card.club, card.league, card.nation).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = V23Muted, fontSize = 12.sp)
                HorizontalDivider()
                Text("Current price", color = V23Muted, fontSize = 12.sp)
                Text(if (price > 0) "${v23Coins(price)} coins" else "No current price", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (trend != 0.0) Text("Recent move ${v23Pct(trend)}", color = if (trend > 0) accent else V23Danger, fontWeight = FontWeight.Bold)
                Card(colors = CardDefaults.cardColors(containerColor = V23Panel)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Aim buy ${if (buy > 0) v23Coins(buy) else "—"}", fontWeight = FontWeight.SemiBold)
                        Text("Aim sell ${if (sell > 0) v23Coins(sell) else "—"}", fontWeight = FontWeight.SemiBold)
                        if (buy > 0 && sell > 0) Text("Estimated profit +${v23Coins(profitAfterTax(buy, sell))} after 5% tax", color = V23Muted, fontSize = 12.sp)
                    }
                }
                Text("Market signal is calculated from the synced price movement and your target ROI. It is guidance, not a guarantee.", color = V23Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(onClick = onBuy) { Text("Log purchase") } },
        dismissButton = {
            Row {
                TextButton(onClick = onToggleWatch) { Text(if (watched) "Unwatch" else "Watch") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

