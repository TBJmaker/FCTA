package com.tbjmaker.fctraderassistant

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FCTraderApp() }
    }
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Success(val count: Int) : SyncStatus
    data class Error(val message: String) : SyncStatus
}

@Composable
fun FCTraderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialBalance = remember { Storage.loadBalance(context) }
    var balance by remember { mutableIntStateOf(initialBalance ?: 0) }
    var needsInitialBalance by remember { mutableStateOf(initialBalance == null) }
    var showBalanceDialog by remember { mutableStateOf(false) }

    var settings by remember { mutableStateOf(Storage.loadSettings(context)) }
    var remoteCards by remember {
        mutableStateOf(Storage.loadRemoteCards(context).ifEmpty { starterCards })
    }
    var customCards by remember { mutableStateOf(Storage.loadCustomCards(context)) }
    var watchlist by remember { mutableStateOf(Storage.loadWatchlist(context)) }
    var trades by remember { mutableStateOf(Storage.loadTrades(context)) }
    var pcOverrides by remember { mutableStateOf(Storage.loadPriceOverrides(context, MarketPlatform.PC)) }
    var consoleOverrides by remember { mutableStateOf(Storage.loadPriceOverrides(context, MarketPlatform.CONSOLE)) }
    var feedMeta by remember { mutableStateOf(Storage.loadFeedMeta(context)) }
    var lastSync by remember { mutableLongStateOf(Storage.loadLastSync(context)) }
    var syncStatus by remember { mutableStateOf<SyncStatus>(SyncStatus.Idle) }

    var tab by remember { mutableIntStateOf(0) }
    var showLiveDatabase by remember { mutableStateOf(false) }
    var cardToBuy by remember { mutableStateOf<CardItem?>(null) }

    val customIds = remember(customCards) { customCards.map { it.id }.toSet() }
    val allCards = remember(remoteCards, customCards) {
        (remoteCards.filterNot { it.id in customIds } + customCards)
            .distinctBy { it.id }
    }

    fun currentPrice(card: CardItem): Int {
        val override = if (settings.platform == MarketPlatform.PC) pcOverrides[card.id] else consoleOverrides[card.id]
        return override ?: card.price(settings.platform)
    }

    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        Storage.saveSettings(context, newSettings)
    }

    fun syncNow() {
        if (syncStatus is SyncStatus.Syncing) return
        scope.launch {
            syncStatus = SyncStatus.Syncing
            runCatching { RemoteData.fetch(settings.remoteUrl) }
                .onSuccess { feed ->
                    remoteCards = feed.cards
                    feedMeta = feed.meta
                    lastSync = System.currentTimeMillis()
                    Storage.saveRemoteFeed(context, feed)
                    syncStatus = SyncStatus.Success(feed.cards.size)
                }
                .onFailure { error ->
                    syncStatus = SyncStatus.Error(error.message ?: "Sync failed")
                }
        }
    }

    LaunchedEffect(settings.autoSync, settings.remoteUrl) {
        if (settings.autoSync) syncNow()
    }

    val bg = Color(0xFF04101B)
    val surface = Color(0xFF0B1B2A)
    val accent = Color(0xFF35F08B)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            secondary = Color(0xFF5CC8FF),
            background = bg,
            surface = surface,
            error = Color(0xFFFF6B6B)
        )
    ) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF071522)) {
                    val names = listOf("Home", "Market", "Portfolio", "Watch", "Analytics", "More")
                    val icons = listOf(
                        Icons.Default.Home,
                        Icons.Default.ShowChart,
                        Icons.Default.AccountBalanceWallet,
                        Icons.Default.Star,
                        Icons.Default.Analytics,
                        Icons.Default.MoreHoriz
                    )
                    names.forEachIndexed { index, name ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                showLiveDatabase = false
                                tab = index
                            },
                            icon = { Icon(icons[index], contentDescription = name) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        ) { padding ->
            if (showLiveDatabase) {
                LiveDatabaseScreen(
                    padding = padding,
                    platform = settings.platform,
                    onClose = { showLiveDatabase = false }
                )
            } else {
                when (tab) {
                    0 -> HomeScreen(
                        padding = padding,
                        balance = balance,
                        trades = trades,
                        cards = allCards,
                        currentPrice = ::currentPrice,
                        platform = settings.platform,
                        syncStatus = syncStatus,
                        lastSync = lastSync,
                        feedMeta = feedMeta,
                        accent = accent,
                        onChangeBalance = { showBalanceDialog = true },
                        onSync = ::syncNow,
                        onOpenMarket = { tab = 1 },
                        onOpenLiveDatabase = { showLiveDatabase = true },
                        onBuy = { cardToBuy = it }
                    )

                    1 -> MarketScreen(
                        padding = padding,
                        cards = allCards,
                        watchlist = watchlist,
                        platform = settings.platform,
                        currentPrice = ::currentPrice,
                        targetRoi = settings.targetRoi,
                        accent = accent,
                        onToggleWatch = { id ->
                            watchlist = if (id in watchlist) watchlist - id else watchlist + id
                            Storage.saveWatchlist(context, watchlist)
                        },
                        onBuy = { cardToBuy = it },
                        onAddCard = { card ->
                            customCards = (customCards + card).distinctBy { it.id }
                            Storage.saveCustomCards(context, customCards)
                        },
                        onDeleteCard = { id ->
                            customCards = customCards.filterNot { it.id == id }
                            watchlist = watchlist - id
                            pcOverrides = pcOverrides - id
                            consoleOverrides = consoleOverrides - id
                            Storage.saveCustomCards(context, customCards)
                            Storage.saveWatchlist(context, watchlist)
                            Storage.savePriceOverrides(context, MarketPlatform.PC, pcOverrides)
                            Storage.savePriceOverrides(context, MarketPlatform.CONSOLE, consoleOverrides)
                        },
                        onPriceOverride = { card, value ->
                            if (settings.platform == MarketPlatform.PC) {
                                pcOverrides = if (value == null) pcOverrides - card.id else pcOverrides + (card.id to value)
                                Storage.savePriceOverrides(context, MarketPlatform.PC, pcOverrides)
                            } else {
                                consoleOverrides = if (value == null) consoleOverrides - card.id else consoleOverrides + (card.id to value)
                                Storage.savePriceOverrides(context, MarketPlatform.CONSOLE, consoleOverrides)
                            }
                        },
                        onOpenLiveDatabase = { showLiveDatabase = true }
                    )

                    2 -> PortfolioScreen(
                        padding = padding,
                        balance = balance,
                        trades = trades,
                        cards = allCards,
                        platform = settings.platform,
                        currentPrice = ::currentPrice,
                        accent = accent,
                        onSell = { trade, soldPrice ->
                            val newBalance = balance + netAfterTax(soldPrice)
                            val updatedTrades = trades.map {
                                if (it.id == trade.id) it.copy(soldPrice = soldPrice, soldAt = System.currentTimeMillis()) else it
                            }
                            balance = newBalance
                            trades = updatedTrades
                            Storage.saveBalance(context, newBalance)
                            Storage.saveTrades(context, updatedTrades)
                        },
                        onCancelTrade = { trade ->
                            val newBalance = balance + trade.buyPrice
                            val updatedTrades = trades.filterNot { it.id == trade.id }
                            balance = newBalance
                            trades = updatedTrades
                            Storage.saveBalance(context, newBalance)
                            Storage.saveTrades(context, updatedTrades)
                        },
                        onBuyCard = { cardToBuy = it },
                        onBuyMore = { tab = 1 }
                    )

                    3 -> WatchlistScreen(
                        padding = padding,
                        cards = allCards.filter { it.id in watchlist },
                        platform = settings.platform,
                        currentPrice = ::currentPrice,
                        targetRoi = settings.targetRoi,
                        accent = accent,
                        onBuy = { cardToBuy = it },
                        onRemove = { id ->
                            watchlist = watchlist - id
                            Storage.saveWatchlist(context, watchlist)
                        },
                        onOpenMarket = { tab = 1 }
                    )

                    4 -> AnalyticsScreen(
                        padding = padding,
                        balance = balance,
                        trades = trades,
                        accent = accent
                    )

                    else -> MoreScreen(
                        padding = padding,
                        balance = balance,
                        settings = settings,
                        syncStatus = syncStatus,
                        lastSync = lastSync,
                        feedMeta = feedMeta,
                        accent = accent,
                        onChangeBalance = { showBalanceDialog = true },
                        onUpdateSettings = ::saveSettings,
                        onSync = ::syncNow,
                        onOpenLiveDatabase = { showLiveDatabase = true },
                        onExportBackup = {
                            copyBackupToClipboard(context)
                        },
                        onImportBackup = { raw ->
                            Storage.importBackup(context, raw)
                                .onSuccess {
                                    balance = Storage.loadBalance(context) ?: 0
                                    settings = Storage.loadSettings(context)
                                    trades = Storage.loadTrades(context)
                                    customCards = Storage.loadCustomCards(context)
                                    watchlist = Storage.loadWatchlist(context)
                                    pcOverrides = Storage.loadPriceOverrides(context, MarketPlatform.PC)
                                    consoleOverrides = Storage.loadPriceOverrides(context, MarketPlatform.CONSOLE)
                                    Toast.makeText(context, "Backup restored", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: "Could not restore backup", Toast.LENGTH_LONG).show()
                                }
                        }
                    )
                }
            }
        }

        if (needsInitialBalance || showBalanceDialog) {
            BalanceDialog(
                currentBalance = if (needsInitialBalance) null else balance,
                canCancel = !needsInitialBalance,
                onDismiss = { showBalanceDialog = false },
                onSave = { newBalance ->
                    balance = newBalance
                    Storage.saveBalance(context, newBalance)
                    needsInitialBalance = false
                    showBalanceDialog = false
                }
            )
        }

        cardToBuy?.let { card ->
            BuyDialog(
                card = card,
                currentPrice = currentPrice(card),
                availableCoins = balance,
                targetRoi = settings.targetRoi,
                onDismiss = { cardToBuy = null },
                onConfirm = { buyPrice, targetSell ->
                    val trade = Trade(
                        id = System.currentTimeMillis(),
                        cardId = card.id,
                        player = card.name,
                        version = card.version,
                        rating = card.rating,
                        position = card.position,
                        buyPrice = buyPrice,
                        targetSellPrice = targetSell,
                        boughtAt = System.currentTimeMillis()
                    )
                    val newBalance = balance - buyPrice
                    val newTrades = trades + trade
                    balance = newBalance
                    trades = newTrades
                    Storage.saveBalance(context, newBalance)
                    Storage.saveTrades(context, newTrades)
                    cardToBuy = null
                    tab = 2
                }
            )
        }
    }
}

private fun copyBackupToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("FC Trader Assistant backup", Storage.exportBackup(context))
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Backup copied to clipboard", Toast.LENGTH_SHORT).show()
}
