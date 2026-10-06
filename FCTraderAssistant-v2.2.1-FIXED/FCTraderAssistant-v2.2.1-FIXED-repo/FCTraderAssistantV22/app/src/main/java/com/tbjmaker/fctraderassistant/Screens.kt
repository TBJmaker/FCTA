package com.tbjmaker.fctraderassistant

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue

private val danger = Color(0xFFFF6B6B)
private val muted = Color(0xFF9DA9B9)

private fun coins(value: Int): String = "%,d".format(value)
private fun pct(value: Double): String = "${if (value > 0) "+" else ""}%.1f%%".format(value)
private fun timeText(value: Long): String = if (value <= 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable
fun Header(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = muted)
        }
    }
}

@Composable
fun StatCard(title: String, value: String, subtitle: String = "", accent: Color) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = muted)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
            if (subtitle.isNotBlank()) Text(subtitle, color = muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun SyncBadge(status: SyncStatus, lastSync: Long, feedMeta: FeedMeta, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val title = when (status) {
                    SyncStatus.Idle -> "Cloud database"
                    SyncStatus.Syncing -> "Syncing database…"
                    is SyncStatus.Success -> "Synced ${status.count} cards"
                    is SyncStatus.Error -> "Using cached data"
                }
                Text(title, fontWeight = FontWeight.Bold)
                val detail = when (status) {
                    is SyncStatus.Error -> status.message
                    else -> {
                        val source = feedMeta.source.ifBlank { "FC Trader Assistant Cloud" }
                        "$source • ${timeText(lastSync)}"
                    }
                }
                Text(detail, color = muted, fontSize = 12.sp, maxLines = 2)
            }
            if (status is SyncStatus.Syncing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onSync) { Icon(Icons.Default.Refresh, "Sync") }
            }
        }
    }
}

@Composable
fun HomeScreen(
    padding: PaddingValues,
    balance: Int,
    trades: List<Trade>,
    cards: List<CardItem>,
    currentPrice: (CardItem) -> Int,
    platform: MarketPlatform,
    syncStatus: SyncStatus,
    lastSync: Long,
    feedMeta: FeedMeta,
    accent: Color,
    onChangeBalance: () -> Unit,
    onSync: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenLiveDatabase: () -> Unit,
    onBuy: (CardItem) -> Unit
) {
    val openTrades = trades.filter { it.soldPrice == null }
    val soldTrades = trades.filter { it.soldPrice != null }
    val realisedProfit = soldTrades.sumOf { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
    val invested = openTrades.sumOf { it.buyPrice }
    val projectedOpenProfit = openTrades.sumOf { profitAfterTax(it.buyPrice, it.targetSellPrice) }
    val opportunities = cards.filter { currentPrice(it) > 0 }.sortedByDescending { it.trend(platform) }.take(5)

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { Header("FC Trader Assistant", "Your FC 27 trading dashboard • ${platform.label} market") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Available coins", coins(balance), "Tap below if your in-game balance changes outside the app", accent)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onChangeBalance, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Balance")
                    }
                    Button(onClick = onOpenMarket, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AddShoppingCart, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Log buy")
                    }
                }
                SyncBadge(syncStatus, lastSync, feedMeta, onSync)
            }
        }
        item {
            Text("Trading snapshot", Modifier.padding(20.dp, 22.dp, 20.dp, 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Open positions", openTrades.size.toString(), "${coins(invested)} coins invested", accent)
                StatCard("Realised profit", "${if (realisedProfit >= 0) "+" else ""}${coins(realisedProfit)}", "Projected open profit: ${if (projectedOpenProfit >= 0) "+" else ""}${coins(projectedOpenProfit)}", if (realisedProfit >= 0) accent else danger)
                OutlinedButton(onClick = onOpenLiveDatabase, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Public, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open live FC 27 card database")
                }
            }
        }
        item {
            Text("Market movers", Modifier.padding(20.dp, 22.dp, 20.dp, 6.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Based on the latest synced feed", Modifier.padding(horizontal = 20.dp), color = muted, fontSize = 12.sp)
        }
        if (opportunities.isEmpty()) {
            item { Text("No priced cards in the local feed yet. Use the live database or add a card manually.", Modifier.padding(20.dp), color = muted) }
        }
        items(opportunities, key = { it.id }) { card ->
            CompactCardRow(card, currentPrice(card), card.trend(platform), accent, onBuy = { onBuy(card) })
        }
    }
}

@Composable
private fun CompactCardRow(
    card: CardItem,
    price: Int,
    trend: Double,
    accent: Color,
    onBuy: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "${card.rating} ${card.position} • ${card.version}",
                    color = muted,
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (price > 0) "${coins(price)} coins" else "No price",
                    fontWeight = FontWeight.SemiBold
                )
                if (trend != 0.0) {
                    Text(
                        pct(trend),
                        color = if (trend >= 0) accent else danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onBuy) {
                Icon(Icons.Default.AddShoppingCart, contentDescription = "Log buy")
            }
        }
    }
}

private enum class MarketView(val label: String) {
    ALL("All"), RISERS("Risers"), FALLERS("Fallers"), BEST_BUYS("Best buys"), BEST_SELLS("Best sells")
}

private enum class TradeSignal(val label: String) {
    BUY("BUY"), SELL("SELL"), HOLD("HOLD")
}

private fun signalForTrend(trend: Double): TradeSignal = when {
    trend >= 3.0 -> TradeSignal.BUY
    trend <= -3.0 -> TradeSignal.SELL
    else -> TradeSignal.HOLD
}

private fun roundedMarketPrice(value: Int): Int {
    if (value <= 0) return 0
    val step = when {
        value >= 100_000 -> 1_000
        value >= 10_000 -> 500
        else -> 100
    }
    return ((value + step / 2) / step) * step
}

private fun targetBuyPrice(current: Int, trend: Double): Int {
    if (current <= 0) return 0
    val discount = when {
        trend >= 10.0 -> 0.97
        trend >= 3.0 -> 0.96
        trend <= -10.0 -> 0.92
        trend <= -3.0 -> 0.94
        else -> 0.95
    }
    return roundedMarketPrice((current * discount).toInt())
}

private fun targetSellPrice(current: Int, trend: Double, roi: Double): Int {
    val buy = targetBuyPrice(current, trend)
    if (buy <= 0) return 0
    return roundedMarketPrice(targetSaleForRoi(buy, roi))
}

@Composable
fun MarketScreen(
    padding: PaddingValues,
    cards: List<CardItem>,
    watchlist: Set<String>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    targetRoi: Double,
    accent: Color,
    onToggleWatch: (String) -> Unit,
    onBuy: (CardItem) -> Unit,
    onAddCard: (CardItem) -> Unit,
    onDeleteCard: (String) -> Unit,
    onPriceOverride: (CardItem, Int?) -> Unit,
    onOpenLiveDatabase: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedView by remember { mutableStateOf(MarketView.ALL) }
    var showAdd by remember { mutableStateOf(false) }
    var priceCard by remember { mutableStateOf<CardItem?>(null) }

    val pricedCards = remember(cards, platform) {
        cards.filter { currentPrice(it) > 0 }
    }
    val q = query.trim()
    val searchResults = remember(cards, q, platform) {
        if (q.length < 2) emptyList() else cards.asSequence()
            .filter { card ->
                listOf(card.name, card.version, card.position, card.club, card.league, card.nation, card.rating.toString())
                    .any { it.contains(q, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<CardItem> { it.name.equals(q, ignoreCase = true) }
                    .thenByDescending { it.name.startsWith(q, ignoreCase = true) }
                    .thenByDescending { it.rating }
                    .thenBy { it.name.lowercase() }
            )
            .take(12)
            .toList()
    }

    val risers = remember(pricedCards, platform) { pricedCards.sortedByDescending { it.trend(platform) }.take(8) }
    val fallers = remember(pricedCards, platform) { pricedCards.sortedBy { it.trend(platform) }.take(8) }
    val bestBuys = remember(pricedCards, platform) {
        pricedCards.filter { signalForTrend(it.trend(platform)) == TradeSignal.BUY }
            .sortedByDescending { it.trend(platform) }.take(8)
    }
    val bestSells = remember(pricedCards, platform) {
        pricedCards.filter { signalForTrend(it.trend(platform)) == TradeSignal.SELL }
            .sortedBy { it.trend(platform) }.take(8)
    }

    val browseList = when (selectedView) {
        MarketView.ALL -> pricedCards.sortedByDescending { it.trend(platform) }
        MarketView.RISERS -> risers
        MarketView.FALLERS -> fallers
        MarketView.BEST_BUYS -> bestBuys
        MarketView.BEST_SELLS -> bestSells
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item { Header("Market", "Find players, see the biggest movers and know what price to aim for") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search players or cards") },
                    placeholder = { Text("e.g. Salah, 89, TOTW…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (q.length >= 2) {
                    if (searchResults.isEmpty()) {
                        Text("No matching players found.", color = muted, fontSize = 12.sp)
                    } else {
                        searchResults.take(6).forEach { card ->
                            MarketSearchSuggestion(
                                card = card,
                                price = currentPrice(card),
                                trend = card.trend(platform),
                                accent = accent,
                                onClick = { onBuy(card) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add card")
                    }
                    OutlinedButton(onClick = onOpenLiveDatabase, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Public, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Live DB")
                    }
                }
            }
        }

        if (q.isBlank()) {
            item {
                Text("Market overview", Modifier.padding(20.dp, 22.dp, 20.dp, 10.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MarketMiniStat("Top rise", risers.firstOrNull()?.let { pct(it.trend(platform)) } ?: "—", accent, Modifier.weight(1f))
                    MarketMiniStat("Top drop", fallers.firstOrNull()?.let { pct(it.trend(platform)) } ?: "—", danger, Modifier.weight(1f))
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarketView.entries.take(3).forEach { view ->
                            FilterChip(
                                selected = selectedView == view,
                                onClick = { selectedView = view },
                                label = { Text(view.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarketView.entries.drop(3).forEach { view ->
                            FilterChip(
                                selected = selectedView == view,
                                onClick = { selectedView = view },
                                label = { Text(view.label) }
                            )
                        }
                    }
                }
            }

            when (selectedView) {
                MarketView.ALL -> {
                    item { MarketSectionHeader("Biggest risers", "Highest % increases") }
                    items(risers.take(5), key = { "r-${it.id}" }) { card ->
                        MarketSignalCard(card, currentPrice(card), card.trend(platform), targetRoi, card.id in watchlist, accent,
                            onWatch = { onToggleWatch(card.id) }, onBuy = { onBuy(card) }, onSetPrice = { priceCard = card }, onDelete = if (card.custom) ({ onDeleteCard(card.id) }) else null)
                    }
                    item { MarketSectionHeader("Biggest fallers", "Largest % decreases") }
                    items(fallers.take(5), key = { "f-${it.id}" }) { card ->
                        MarketSignalCard(card, currentPrice(card), card.trend(platform), targetRoi, card.id in watchlist, accent,
                            onWatch = { onToggleWatch(card.id) }, onBuy = { onBuy(card) }, onSetPrice = { priceCard = card }, onDelete = if (card.custom) ({ onDeleteCard(card.id) }) else null)
                    }
                }
                else -> {
                    item { MarketSectionHeader(selectedView.label, "${browseList.size} matching cards") }
                    items(browseList, key = { "v-${it.id}" }) { card ->
                        MarketSignalCard(card, currentPrice(card), card.trend(platform), targetRoi, card.id in watchlist, accent,
                            onWatch = { onToggleWatch(card.id) }, onBuy = { onBuy(card) }, onSetPrice = { priceCard = card }, onDelete = if (card.custom) ({ onDeleteCard(card.id) }) else null)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddCardDialog(
            platform = platform,
            onDismiss = { showAdd = false },
            onSave = {
                onAddCard(it)
                showAdd = false
            }
        )
    }

    priceCard?.let { card ->
        PriceOverrideDialog(
            card = card,
            currentPrice = currentPrice(card),
            platform = platform,
            onDismiss = { priceCard = null },
            onSave = { value ->
                onPriceOverride(card, value)
                priceCard = null
            }
        )
    }
}

@Composable
private fun MarketMiniStat(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
    }
}

@Composable
private fun MarketSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun MarketSearchSuggestion(card: CardItem, price: Int, trend: Double, accent: Color, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text("${card.rating} ${card.position} • ${card.version}", color = muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (price > 0) coins(price) else "No price", fontWeight = FontWeight.SemiBold)
                if (trend != 0.0) Text(pct(trend), color = if (trend >= 0) accent else danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MarketSignalCard(
    card: CardItem,
    price: Int,
    trend: Double,
    targetRoi: Double,
    watched: Boolean,
    accent: Color,
    onWatch: () -> Unit,
    onBuy: () -> Unit,
    onSetPrice: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val signal = signalForTrend(trend)
    val buyTarget = targetBuyPrice(price, trend)
    val sellTarget = targetSellPrice(price, trend, targetRoi)
    val signalColor = when (signal) {
        TradeSignal.BUY -> accent
        TradeSignal.SELL -> danger
        TradeSignal.HOLD -> Color(0xFFFFC857)
    }

    Card(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${card.rating} ${card.position} • ${card.version}", color = muted)
                    val meta = listOf(card.club, card.league).filter { it.isNotBlank() }.joinToString(" • ")
                    if (meta.isNotBlank()) Text(meta, color = muted, fontSize = 12.sp)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(signal.label, fontWeight = FontWeight.Bold) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = signalColor)
                )
                IconButton(onClick = onWatch) {
                    Icon(if (watched) Icons.Default.Star else Icons.Default.StarBorder, "Watch", tint = if (watched) Color(0xFFFFD166) else LocalContentColor.current)
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Current price", color = muted, fontSize = 12.sp)
                    Text(if (price > 0) "${coins(price)} coins" else "No price", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                if (trend != 0.0) {
                    Text(pct(trend), color = if (trend >= 0) accent else danger, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PriceAim("Aim to buy", buyTarget, accent, Modifier.weight(1f))
                PriceAim("Aim to sell", sellTarget, accent, Modifier.weight(1f))
            }

            Text(
                when (signal) {
                    TradeSignal.BUY -> "Price momentum is positive. Look for an entry near the buy target rather than chasing the current price."
                    TradeSignal.SELL -> "Price is falling. If you already hold this card, consider selling near the target before further downside."
                    TradeSignal.HOLD -> "Movement is fairly flat. Wait for a better entry or a clearer trend before committing coins."
                },
                color = muted,
                fontSize = 12.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBuy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AddShoppingCart, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Log buy")
                }
                OutlinedButton(onClick = onSetPrice, modifier = Modifier.weight(1f)) { Text("Update price") }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun PriceAim(title: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = muted, fontSize = 11.sp)
            Text(if (value > 0) coins(value) else "—", color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun PortfolioScreen(
    padding: PaddingValues,
    balance: Int,
    trades: List<Trade>,
    cards: List<CardItem>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    accent: Color,
    onSell: (Trade, Int) -> Unit,
    onCancelTrade: (Trade) -> Unit,
    onBuyCard: (CardItem) -> Unit,
    onBuyMore: () -> Unit
) {
    var showSold by remember { mutableStateOf(false) }
    var sellTrade by remember { mutableStateOf<Trade?>(null) }
    var cancelTrade by remember { mutableStateOf<Trade?>(null) }
    var playerQuery by remember { mutableStateOf("") }

    val list = if (showSold) trades.filter { it.soldPrice != null } else trades.filter { it.soldPrice == null }
    val cardMap = remember(cards) { cards.associateBy { it.id } }
    val suggestions = remember(cards, playerQuery) {
        val q = playerQuery.trim()
        if (q.length < 2) emptyList() else cards
            .asSequence()
            .filter { card ->
                card.name.contains(q, ignoreCase = true) ||
                    card.version.contains(q, ignoreCase = true) ||
                    card.position.contains(q, ignoreCase = true) ||
                    card.rating.toString().startsWith(q)
            }
            .sortedWith(
                compareByDescending<CardItem> { it.name.equals(q, ignoreCase = true) }
                    .thenByDescending { it.name.startsWith(q, ignoreCase = true) }
                    .thenBy { it.name.lowercase() }
                    .thenByDescending { it.rating }
            )
            .take(6)
            .toList()
    }

    LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Portfolio", "Search a player to log a buy, then manage your trades") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Available coins", coins(balance), "${platform.label} market", accent)

                OutlinedTextField(
                    value = playerQuery,
                    onValueChange = { playerQuery = it },
                    label = { Text("Search player") },
                    placeholder = { Text("e.g. Donnarumma, Ekitike, Salah…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (playerQuery.isNotBlank()) {
                            IconButton(onClick = { playerQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (playerQuery.trim().length >= 2) {
                    if (suggestions.isEmpty()) {
                        Text("No matching cards. Try the Market tab or add a custom card.", color = muted, fontSize = 12.sp)
                    } else {
                        suggestions.forEach { card ->
                            val price = currentPrice(card)
                            Card(
                                onClick = {
                                    playerQuery = ""
                                    onBuyCard(card)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(card.name, fontWeight = FontWeight.Bold)
                                        Text("${card.rating} ${card.position} • ${card.version}", color = muted, fontSize = 12.sp)
                                    }
                                    Text(if (price > 0) coins(price) else "No price", color = if (price > 0) accent else muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showSold, onClick = { showSold = false }, label = { Text("Open (${trades.count { it.soldPrice == null }})") })
                    FilterChip(selected = showSold, onClick = { showSold = true }, label = { Text("Sold (${trades.count { it.soldPrice != null }})") })
                }
                OutlinedButton(onClick = onBuyMore, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ShowChart, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Browse full market")
                }
            }
        }
        if (list.isEmpty()) {
            item { Text(if (showSold) "No completed trades yet." else "No open positions yet.", Modifier.padding(20.dp), color = muted) }
        }
        items(list.sortedByDescending { it.boughtAt }, key = { it.id }) { trade ->
            val liveCard = cardMap[trade.cardId]
            val livePrice = liveCard?.let(currentPrice) ?: 0
            TradeCard(
                trade = trade,
                livePrice = livePrice,
                accent = accent,
                onSell = if (trade.soldPrice == null) ({ sellTrade = trade }) else null,
                onCancel = if (trade.soldPrice == null) ({ cancelTrade = trade }) else null
            )
        }
    }

    sellTrade?.let { trade ->
        SellDialog(trade = trade, onDismiss = { sellTrade = null }) { sold ->
            onSell(trade, sold)
            sellTrade = null
        }
    }

    cancelTrade?.let { trade ->
        AlertDialog(
            onDismissRequest = { cancelTrade = null },
            title = { Text("Cancel logged purchase?") },
            text = { Text("This removes ${trade.player} from your open trades and refunds ${coins(trade.buyPrice)} coins to the app balance. Use this to correct a mistaken entry.") },
            confirmButton = {
                Button(onClick = {
                    onCancelTrade(trade)
                    cancelTrade = null
                }) { Text("Cancel trade") }
            },
            dismissButton = { TextButton(onClick = { cancelTrade = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun TradeCard(trade: Trade, livePrice: Int, accent: Color, onSell: (() -> Unit)?, onCancel: (() -> Unit)?) {
    val referenceSell = trade.soldPrice ?: if (livePrice > 0) livePrice else trade.targetSellPrice
    val profit = profitAfterTax(trade.buyPrice, referenceSell)
    val roi = roiAfterTax(trade.buyPrice, referenceSell)

    Card(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(trade.player, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${trade.rating} ${trade.position} • ${trade.version}", color = muted)
                }
                Text(if (trade.soldPrice == null) "OPEN" else "SOLD", color = if (trade.soldPrice == null) muted else accent, fontWeight = FontWeight.Bold)
            }
            Text("Bought: ${coins(trade.buyPrice)} • Break-even: ${coins(breakEvenSalePrice(trade.buyPrice))}", color = muted)
            if (trade.soldPrice == null) {
                Text("Target: ${coins(trade.targetSellPrice)}${if (livePrice > 0) " • Current: ${coins(livePrice)}" else ""}", color = muted)
            } else {
                Text("Sold: ${coins(trade.soldPrice)} • Net: ${coins(netAfterTax(trade.soldPrice))}", color = muted)
            }
            Text("${if (profit >= 0) "+" else ""}${coins(profit)} coins • ${pct(roi)} ROI", color = if (profit >= 0) accent else danger, fontWeight = FontWeight.Bold)
            if (onSell != null && onCancel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSell, modifier = Modifier.weight(1f)) { Text("Mark sold") }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Undo buy") }
                }
            }
        }
    }
}

@Composable
fun WatchlistScreen(
    padding: PaddingValues,
    cards: List<CardItem>,
    platform: MarketPlatform,
    currentPrice: (CardItem) -> Int,
    targetRoi: Double,
    accent: Color,
    onBuy: (CardItem) -> Unit,
    onRemove: (String) -> Unit,
    onOpenMarket: () -> Unit
) {
    LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Watchlist", "Cards you want to keep an eye on") }
        if (cards.isEmpty()) {
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your watchlist is empty.", color = muted)
                    Button(onClick = onOpenMarket) { Text("Browse market") }
                }
            }
        }
        items(cards.sortedByDescending { it.trend(platform) }, key = { it.id }) { card ->
            val price = currentPrice(card)
            Card(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${card.rating} ${card.position} • ${card.version}", color = muted)
                        }
                        IconButton(onClick = { onRemove(card.id) }) { Icon(Icons.Default.Star, "Remove") }
                    }
                    if (price > 0) {
                        val desiredBuy = ((netAfterTax(price) / (1.0 + targetRoi / 100.0))).toInt()
                        Text("Current: ${coins(price)} • Trend: ${pct(card.trend(platform))}", color = muted)
                        Text("For ~${targetRoi.toInt()}% ROI at this sell price, buy around ${coins(desiredBuy)} or lower", color = accent, fontSize = 12.sp)
                    } else {
                        Text("No synced price. Set one manually in Market.", color = muted)
                    }
                    Button(onClick = { onBuy(card) }, modifier = Modifier.fillMaxWidth()) { Text("Log purchase") }
                }
            }
        }
    }
}

@Composable
fun AnalyticsScreen(padding: PaddingValues, balance: Int, trades: List<Trade>, accent: Color) {
    val open = trades.filter { it.soldPrice == null }
    val sold = trades.filter { it.soldPrice != null }
    val realised = sold.sumOf { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
    val projected = open.sumOf { profitAfterTax(it.buyPrice, it.targetSellPrice) }
    val invested = open.sumOf { it.buyPrice }
    val totalTurnover = sold.sumOf { it.buyPrice }
    val wins = sold.count { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) > 0 }
    val losses = sold.count { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) < 0 }
    val winRate = if (sold.isEmpty()) 0.0 else wins * 100.0 / sold.size
    val avgProfit = if (sold.isEmpty()) 0 else realised / sold.size
    val best = sold.maxByOrNull { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }
    val worst = sold.minByOrNull { profitAfterTax(it.buyPrice, it.soldPrice ?: 0) }

    LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("Analytics", "Your trading performance", Modifier.padding(0.dp)) }
        item { StatCard("Current balance", coins(balance), "Open investment: ${coins(invested)}", accent) }
        item { StatCard("Realised profit", "${if (realised >= 0) "+" else ""}${coins(realised)}", "Projected open profit: ${if (projected >= 0) "+" else ""}${coins(projected)}", if (realised >= 0) accent else danger) }
        item { StatCard("Completed trades", sold.size.toString(), "$wins wins • $losses losses • %.1f%% win rate".format(winRate), accent) }
        item { StatCard("Average profit", "${if (avgProfit >= 0) "+" else ""}${coins(avgProfit)}", "Total completed buy volume: ${coins(totalTurnover)}", if (avgProfit >= 0) accent else danger) }
        if (best != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Best trade", color = muted)
                        Text("${best.player} • ${best.version}", fontWeight = FontWeight.Bold)
                        Text("+${coins(profitAfterTax(best.buyPrice, best.soldPrice ?: 0).coerceAtLeast(0))} coins", color = accent)
                    }
                }
            }
        }
        if (worst != null && profitAfterTax(worst.buyPrice, worst.soldPrice ?: 0) < 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Largest loss", color = muted)
                        Text("${worst.player} • ${worst.version}", fontWeight = FontWeight.Bold)
                        Text("-${coins(profitAfterTax(worst.buyPrice, worst.soldPrice ?: 0).absoluteValue)} coins", color = danger)
                    }
                }
            }
        }
    }
}

@Composable
fun MoreScreen(
    padding: PaddingValues,
    balance: Int,
    settings: AppSettings,
    syncStatus: SyncStatus,
    lastSync: Long,
    feedMeta: FeedMeta,
    accent: Color,
    onChangeBalance: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onSync: () -> Unit,
    onOpenLiveDatabase: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: (String) -> Unit
) {
    var remoteUrl by remember(settings.remoteUrl) { mutableStateOf(settings.remoteUrl) }
    var roiText by remember(settings.targetRoi) { mutableStateOf(settings.targetRoi.toString().removeSuffix(".0")) }
    var showImport by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        Header("More", "Settings, live database and backup")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Coin balance", coins(balance), "Manual correction is always available", accent)
            OutlinedButton(onClick = onChangeBalance, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("Change balance")
            }

            Text("Market platform", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.platform == MarketPlatform.PC,
                    onClick = { onUpdateSettings(settings.copy(platform = MarketPlatform.PC)) },
                    label = { Text("PC") }
                )
                FilterChip(
                    selected = settings.platform == MarketPlatform.CONSOLE,
                    onClick = { onUpdateSettings(settings.copy(platform = MarketPlatform.CONSOLE)) },
                    label = { Text("Console") }
                )
            }

            OutlinedTextField(
                value = roiText,
                onValueChange = { value ->
                    roiText = value.filter { it.isDigit() || it == '.' }.take(5)
                },
                label = { Text("Default target ROI %") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val roi = roiText.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: settings.targetRoi
                    onUpdateSettings(settings.copy(targetRoi = roi))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save target ROI") }

            HorizontalDivider()
            Text("Card database", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            SyncBadge(syncStatus, lastSync, feedMeta, onSync)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-sync when the app opens", Modifier.weight(1f))
                Switch(
                    checked = settings.autoSync,
                    onCheckedChange = { onUpdateSettings(settings.copy(autoSync = it)) }
                )
            }
            OutlinedTextField(
                value = remoteUrl,
                onValueChange = { remoteUrl = it.trim() },
                label = { Text("Cloud data URL") },
                supportingText = { Text("Must be an HTTPS JSON feed") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val url = remoteUrl.ifBlank { DEFAULT_REMOTE_URL }
                        onUpdateSettings(settings.copy(remoteUrl = url))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save source") }
                OutlinedButton(
                    onClick = {
                        remoteUrl = DEFAULT_REMOTE_URL
                        onUpdateSettings(settings.copy(remoteUrl = DEFAULT_REMOTE_URL))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
            }
            OutlinedButton(onClick = onOpenLiveDatabase, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Public, null)
                Spacer(Modifier.width(8.dp))
                Text("Open FUT.GG live FC 27 database")
            }
            Text("The live database is separate from your EA account. The app does not automate the Companion App or log in to EA.", color = muted, fontSize = 12.sp)

            HorizontalDivider()
            Text("Backup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Copy your balance, trades, watchlist, custom cards and settings before changing phones or testing a new build.", color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("Copy backup") }
                OutlinedButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) { Text("Restore") }
            }

            Spacer(Modifier.height(8.dp))
            Text("FC Trader Assistant v2.2", color = muted, fontSize = 12.sp)
        }
    }

    if (showImport) {
        ImportBackupDialog(onDismiss = { showImport = false }) { raw ->
            onImportBackup(raw)
            showImport = false
        }
    }
}

@Composable
fun LiveDatabaseScreen(padding: PaddingValues, platform: MarketPlatform, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    BackHandler(enabled = true) {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    Column(Modifier.padding(padding).fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (webView?.canGoBack() == true) webView?.goBack() else onClose()
            }) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Live FC 27 database", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("FUT.GG • ${platform.label}", color = muted, fontSize = 12.sp)
            }
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }
                    }
                    loadUrl(LIVE_DATABASE_URL)
                    webView = this
                }
            },
            update = { webView = it }
        )
    }
}

@Composable
fun BalanceDialog(currentBalance: Int?, canCancel: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember(currentBalance) { mutableStateOf(currentBalance?.toString() ?: "") }
    val parsed = value.toIntOrNull()

    AlertDialog(
        onDismissRequest = { if (canCancel) onDismiss() },
        title = { Text(if (currentBalance == null) "Set your coin balance" else "Change coin balance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (currentBalance == null) "Enter the coins currently shown in FC. This is not a fixed budget—you can change it whenever you need." else "Set this to the balance currently shown in FC. Logged buys and sales will update it automatically afterwards.")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(9) },
                    label = { Text("Current coins") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed >= 0) { Text("Save") }
        },
        dismissButton = if (canCancel) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null
    )
}

@Composable
fun BuyDialog(
    card: CardItem,
    currentPrice: Int,
    availableCoins: Int,
    targetRoi: Double,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var buyText by remember(card.id) { mutableStateOf(if (currentPrice > 0) currentPrice.toString() else "") }
    var targetText by remember(card.id) { mutableStateOf("") }
    val buy = buyText.toIntOrNull()
    val suggestedTarget = buy?.let { targetSaleForRoi(it, targetRoi) } ?: 0
    val target = targetText.toIntOrNull() ?: suggestedTarget
    val profit = if (buy != null && target > 0) profitAfterTax(buy, target) else 0
    val roi = if (buy != null && target > 0) roiAfterTax(buy, target) else 0.0

    LaunchedEffect(suggestedTarget) {
        if (targetText.isBlank() && suggestedTarget > 0) targetText = suggestedTarget.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log purchase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${card.name} • ${card.rating} ${card.position} • ${card.version}", fontWeight = FontWeight.Bold)
                OutlinedTextField(buyText, { buyText = it.filter(Char::isDigit).take(9) }, label = { Text("Buy price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(targetText, { targetText = it.filter(Char::isDigit).take(9) }, label = { Text("Target sell") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (buy != null && buy > 0 && target > 0) {
                    Text("Break-even: ${coins(breakEvenSalePrice(buy))}", color = muted)
                    Text("After 5% tax: ${coins(netAfterTax(target))} • Profit ${if (profit >= 0) "+" else ""}${coins(profit)} • ${pct(roi)} ROI", color = if (profit >= 0) MaterialTheme.colorScheme.primary else danger)
                }
                if (buy != null && buy > availableCoins) Text("You only have ${coins(availableCoins)} available in the app.", color = danger)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (buy != null) onConfirm(buy, target) },
                enabled = buy != null && buy > 0 && buy <= availableCoins && target > 0
            ) { Text("Log buy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SellDialog(trade: Trade, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember(trade.id) { mutableStateOf(trade.targetSellPrice.toString()) }
    val sold = value.toIntOrNull()
    val profit = sold?.let { profitAfterTax(trade.buyPrice, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark ${trade.player} sold") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(value, { value = it.filter(Char::isDigit).take(9) }, label = { Text("Actual sell price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (sold != null && sold > 0) {
                    Text("EA tax: ${coins(sold - netAfterTax(sold))}", color = muted)
                    Text("Coins added: ${coins(netAfterTax(sold))}", color = muted)
                    Text("Profit: ${if ((profit ?: 0) >= 0) "+" else ""}${coins(profit ?: 0)}", color = if ((profit ?: 0) >= 0) MaterialTheme.colorScheme.primary else danger)
                }
            }
        },
        confirmButton = { Button(onClick = { sold?.let(onConfirm) }, enabled = sold != null && sold > 0) { Text("Confirm sale") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddCardDialog(platform: MarketPlatform, onDismiss: () -> Unit, onSave: (CardItem) -> Unit) {
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var club by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val ratingValue = rating.toIntOrNull()
    val priceValue = price.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add missing card") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Player name") }, singleLine = true)
                OutlinedTextField(version, { version = it }, label = { Text("Card version / promo") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rating, { rating = it.filter(Char::isDigit).take(2) }, label = { Text("Rating") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(position, { position = it.uppercase().take(4) }, label = { Text("Position") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(club, { club = it }, label = { Text("Club (optional)") }, singleLine = true)
                OutlinedTextField(price, { price = it.filter(Char::isDigit).take(9) }, label = { Text("${platform.label} price (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val id = (name + "-" + version + "-" + rating + "-" + System.currentTimeMillis()).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    onSave(
                        CardItem(
                            id = id,
                            name = name.trim(),
                            version = version.trim().ifBlank { "Custom" },
                            rating = ratingValue ?: 0,
                            position = position.trim(),
                            club = club.trim(),
                            pricePc = if (platform == MarketPlatform.PC) priceValue else 0,
                            priceConsole = if (platform == MarketPlatform.CONSOLE) priceValue else 0,
                            source = "Manual",
                            custom = true
                        )
                    )
                },
                enabled = name.isNotBlank() && version.isNotBlank() && ratingValue != null && ratingValue in 1..99 && position.isNotBlank()
            ) { Text("Add card") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PriceOverrideDialog(card: CardItem, currentPrice: Int, platform: MarketPlatform, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var value by remember(card.id) { mutableStateOf(if (currentPrice > 0) currentPrice.toString() else "") }
    val parsed = value.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set local price") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${card.name} • ${card.version}")
                Text("This overrides the synced ${platform.label} price on this device until you clear it.", color = muted)
                OutlinedTextField(value, { value = it.filter(Char::isDigit).take(9) }, label = { Text("Price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(parsed?.takeIf { it > 0 }) }, enabled = parsed != null && parsed > 0) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Clear override") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun ImportBackupDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the backup text you copied from FC Trader Assistant.")
                OutlinedTextField(raw, { raw = it }, label = { Text("Backup JSON") }, minLines = 7, maxLines = 12, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onImport(raw) }, enabled = raw.isNotBlank()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
