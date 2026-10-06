package com.tbjmaker.fctraderassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val CHANNEL_ID = "market_alerts"
private const val CHANNEL_NAME = "FC Trader market alerts"
private const val WORK_NAME = "fc_trader_market_watch"

object NotificationTools {
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Price moves, Trader AI ideas, portfolio targets and daily briefings"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun configureBackgroundChecks(context: Context, prefs: NotificationPrefs) {
        createChannel(context)
        val workManager = WorkManager.getInstance(context)
        if (!prefs.enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MarketAlertWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

class MarketAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Storage.loadNotificationPrefs(applicationContext)
        if (!prefs.enabled) return Result.success()

        val settings = Storage.loadSettings(applicationContext)
        val balance = Storage.loadBalance(applicationContext) ?: 0
        val trades = Storage.loadTrades(applicationContext)

        return runCatching {
            val feed = RemoteData.fetch(settings.remoteUrl)
            Storage.saveRemoteFeed(applicationContext, feed)
            val cards = feed.cards
            val now = System.currentTimeMillis()

            if (prefs.dailyBriefing && now - Storage.getLastDailyBriefing(applicationContext) >= 20L * 60L * 60L * 1000L) {
                val priced = cards.filter { it.price(settings.platform) > 0 }
                val topRise = priced.maxByOrNull { it.trend(settings.platform) }
                val topDrop = priced.minByOrNull { it.trend(settings.platform) }
                val message = buildString {
                    if (topRise != null) append("Top riser: ${topRise.name} ${formatSigned(topRise.trend(settings.platform))}. ")
                    if (topDrop != null && topDrop.trend(settings.platform) < 0) append("Top faller: ${topDrop.name} ${formatSigned(topDrop.trend(settings.platform))}. ")
                    append("Open Trader AI for ideas matched to your ${formatCoins(balance)} coin balance.")
                }
                NotificationTools.post(applicationContext, 2301, "Your FC Trader daily briefing", message)
                Storage.setLastDailyBriefing(applicationContext, now)
            }

            if (prefs.marketMoves) {
                val mover = cards
                    .filter { it.price(settings.platform) > 0 && abs(it.trend(settings.platform)) >= 5.0 }
                    .maxByOrNull { abs(it.trend(settings.platform)) }
                if (mover != null) {
                    val signature = "${mover.id}:${"%.1f".format(mover.trend(settings.platform))}"
                    if (signature != Storage.getLastMarketAlert(applicationContext)) {
                        NotificationTools.post(
                            applicationContext,
                            2302,
                            "Market move: ${mover.name}",
                            "${mover.version} is ${formatSigned(mover.trend(settings.platform))} at about ${formatCoins(mover.price(settings.platform))} coins."
                        )
                        Storage.setLastMarketAlert(applicationContext, signature)
                    }
                }
            }

            if (prefs.aiIdeas && balance > 0) {
                val idea = buildAiIdeas(cards, balance, settings.platform, settings.targetRoi, limit = 1).firstOrNull()
                if (idea != null && idea.signal == TradeSignal.BUY) {
                    val signature = "${idea.card.id}:${idea.targetBuy}:${idea.targetSell}"
                    if (signature != Storage.getLastAiAlert(applicationContext)) {
                        NotificationTools.post(
                            applicationContext,
                            2303,
                            "Trader AI found an idea",
                            "${idea.card.name} ${idea.card.version}: aim to buy around ${formatCoins(idea.targetBuy)} and sell around ${formatCoins(idea.targetSell)}. ${idea.risk} risk."
                        )
                        Storage.setLastAiAlert(applicationContext, signature)
                    }
                }
            }

            if (prefs.portfolioTargets) {
                val byId = cards.associateBy { it.id }
                val hit = trades.asSequence()
                    .filter { it.soldPrice == null }
                    .mapNotNull { trade ->
                        val card = byId[trade.cardId] ?: return@mapNotNull null
                        val current = card.price(settings.platform)
                        if (current <= 0) return@mapNotNull null
                        val roi = roiAfterTax(trade.buyPrice, current)
                        if (roi >= settings.targetRoi) Triple(trade, current, roi) else null
                    }
                    .maxByOrNull { it.third }
                if (hit != null) {
                    val signature = "${hit.first.id}:${hit.second}"
                    if (signature != Storage.getLastPortfolioAlert(applicationContext)) {
                        NotificationTools.post(
                            applicationContext,
                            2304,
                            "Portfolio target reached",
                            "${hit.first.player} is around ${formatCoins(hit.second)}. Selling there would be about ${"%.1f".format(hit.third)}% ROI after EA tax."
                        )
                        Storage.setLastPortfolioAlert(applicationContext, signature)
                    }
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun formatSigned(value: Double): String = "${if (value > 0) "+" else ""}${"%.1f".format(value)}%"
    private fun formatCoins(value: Int): String = "%,d".format(value)
}
