package com.tbjmaker.fctraderassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationTools.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2300)
        }
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
    var notificationPrefs by remember { mutableStateOf(Storage.loadNotificationPrefs(context)) }
    var remoteCards by remember { mutableStateOf(Storage.loadRemoteCards(context).ifEmpty { starterCards }) }
    var customCards by remember { mutableStateOf(Storage.loadCustomCards(context)) }
    var watchlist by remember { mutableStateOf(Storage.loadWatchlist(context)) }
    var trades by remember { mutableStateOf(Storage.loadTrades(context)) }
    var pcOverrides by remember { mutableStateOf(Storage.loadPriceOverrides(context, MarketPlatform.PC)) }
    var consoleOverrides by remember { mutableStateOf(Storage.loadPriceOverrides(context, MarketPlatform.CONSOLE)) }
    var feedMeta by remember { mutableStateOf(Storage.loadFeedMeta(context)) }
    var lastSync by remember { mutableLongStateOf(Storage.loadLastSync(context)) }
    var syncStatus by remember { mutableStateOf<SyncStatus>(SyncStatus.Idle) }

    var tab by remember { mutableIntStateOf(0) }
    var cardToBuy by remember { mutableStateOf<CardItem?>(null) }
    var cardToInspect by remember { mutableStateOf<CardItem?>(null) }

    val customIds = remember(customCards) { customCards.map { it.id }.toSet() }
    val allCards = remember(remoteCards, customCards) {
        (remoteCards.filterNot { it.id in customIds } + customCards).distinctBy { it.id }
    }

    fun currentPrice(card: CardItem): Int {
        val override = if (settings.platform == MarketPlatform.PC) pcOverrides[card.id] else consoleOverrides[card.id]
        return override ?: card.price(settings.platform)
    }

    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        Storage.saveSettings(context, newSettings)
    }

    fun saveNotifications(newPrefs: NotificationPrefs) {
        notificationPrefs = newPrefs
        Storage.saveNotificationPrefs(context, newPrefs)
        NotificationTools.configureBackgroundChecks(context, newPrefs)
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
                .onFailure { error -> syncStatus = SyncStatus.Error(error.message ?: "Sync failed") }
        }
    }

    LaunchedEffect(settings.autoSync, settings.remoteUrl) {
        if (settings.autoSync) syncNow()
    }

    LaunchedEffect(notificationPrefs) {
        NotificationTools.configureBackgroundChecks(context, notificationPrefs)
    }

    val bg = Color(0xFF050B12)
    val surface = Color(0xFF0D1722)
    val accent = Color(0xFF36E58D)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            secondary = Color(0xFF67C7FF),
            background = bg,
            surface = surface,
            surfaceVariant = Color(0xFF122333),
            onSurface = Color(0xFFF2F5F8),
            error = Color(0xFFFF6B6B)
        )
    ) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF07111B)) {
                    val names = listOf("Home", "Market", "Trader AI", "Portfolio", "More")
                    val icons = listOf(
                        Icons.Default.Home,
                        Icons.Default.ShowChart,
                        Icons.Default.AutoAwesome,
                        Icons.Default.AccountBalanceWallet,
                        Icons.Default.MoreHoriz
                    )
                    names.forEachIndexed { index, name ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icons[index], contentDescription = name) },
                            label = { Text(name, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> V23HomeScreen(
                    padding = padding,
                    balance = balance,
                    trades = trades,
                    cards = allCards,
                    platform = settings.platform,
                    currentPrice = ::currentPrice,
                    targetRoi = settings.targetRoi,
                    lastSync = lastSync,
                    syncStatus = syncStatus,
                    accent = accent,
                    onChangeBalance = { showBalanceDialog = true },
                    onSync = ::syncNow,
                    onOpenMarket = { tab = 1 },
                    onOpenAi = { tab = 2 },
                    onOpenCard = { cardToInspect = it }
                )

                1 -> V23MarketScreen(
                    padding = padding,
                    cards = allCards,
                    watchlist = watchlist,
                    platform = settings.platform,
                    currentPrice = ::currentPrice,
                    targetRoi = settings.targetRoi,
                    lastSync = lastSync,
                    accent = accent,
                    onToggleWatch = { id ->
                        watchlist = if (id in watchlist) watchlist - id else watchlist + id
                        Storage.saveWatchlist(context, watchlist)
                    },
                    onOpenCard = { cardToInspect = it }
                )

                2 -> TraderAiScreen(
                    padding = padding,
                    balance = balance,
                    cards = allCards,
                    trades = trades,
                    platform = settings.platform,
                    targetRoi = settings.targetRoi,
                    currentPrice = ::currentPrice,
                    accent = accent,
                    onOpenCard = { cardToInspect = it }
                )

                3 -> V23PortfolioScreen(
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
                    onBuyCard = { cardToBuy = it }
                )

                else -> V23MoreScreen(
                    padding = padding,
                    balance = balance,
                    settings = settings,
                    notificationPrefs = notificationPrefs,
                    cards = allCards,
                    watchlist = watchlist,
                    trades = trades,
                    platform = settings.platform,
                    currentPrice = ::currentPrice,
                    targetRoi = settings.targetRoi,
                    lastSync = lastSync,
                    syncStatus = syncStatus,
                    accent = accent,
                    onChangeBalance = { showBalanceDialog = true },
                    onUpdateSettings = ::saveSettings,
                    onUpdateNotifications = ::saveNotifications,
                    onSync = ::syncNow,
                    onToggleWatch = { id ->
                        watchlist = if (id in watchlist) watchlist - id else watchlist + id
                        Storage.saveWatchlist(context, watchlist)
                    },
                    onBuy = { cardToBuy = it },
                    onExportBackup = { copyBackupToClipboard(context) },
                    onImportBackup = { raw ->
                        Storage.importBackup(context, raw)
                            .onSuccess {
                                balance = Storage.loadBalance(context) ?: 0
                                settings = Storage.loadSettings(context)
                                notificationPrefs = Storage.loadNotificationPrefs(context)
                                trades = Storage.loadTrades(context)
                                customCards = Storage.loadCustomCards(context)
                                watchlist = Storage.loadWatchlist(context)
                                pcOverrides = Storage.loadPriceOverrides(context, MarketPlatform.PC)
                                consoleOverrides = Storage.loadPriceOverrides(context, MarketPlatform.CONSOLE)
                                NotificationTools.configureBackgroundChecks(context, notificationPrefs)
                                Toast.makeText(context, "Backup restored", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: "Could not restore backup", Toast.LENGTH_LONG).show() }
                    }
                )
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

        cardToInspect?.let { card ->
            V23PlayerDetailDialog(
                card = card,
                price = currentPrice(card),
                platform = settings.platform,
                targetRoi = settings.targetRoi,
                watched = card.id in watchlist,
                accent = accent,
                onDismiss = { cardToInspect = null },
                onToggleWatch = {
                    watchlist = if (card.id in watchlist) watchlist - card.id else watchlist + card.id
                    Storage.saveWatchlist(context, watchlist)
                },
                onBuy = {
                    cardToInspect = null
                    cardToBuy = card
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
                    tab = 3
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
