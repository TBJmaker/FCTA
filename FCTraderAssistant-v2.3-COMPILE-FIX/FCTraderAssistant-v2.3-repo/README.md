# FC Trader Assistant v2.3

Android trading companion for EA SPORTS FC 27 Ultimate Team. v2.3 is built around one synced card database, cleaner market rankings, a grounded Trader AI analysis engine, portfolio tracking and Android notifications.

## What changed in v2.3

- Five-item bottom navigation: **Home · Market · Trader AI · Portfolio · More**
- Cleaner dark UI; green/red/amber are reserved for meaningful BUY/SELL/HOLD and market movement signals
- Small date/time **last updated** text instead of large sync messaging
- One master synced card list powers Home, Market, Portfolio, Watchlist and Trader AI
- Search matches **player name, team/club, league, nation, position, rating and card/promo version**
- Market advanced filters: team, league, nation, position, card/promo, minimum rating and maximum price
- Separate **Top 10 Risers, Top 10 Fallers, Top 10 Best Buys and Top 10 Best Sells**
- Fallers only contain negative moves; risers only contain positive moves
- BUY / SELL / HOLD is a status badge, not a button
- Tap a market card for a native player analysis view with target buy, target sell and estimated profit after EA tax
- Home replaces duplicated market movers with **Quick Player Search** and a **Trader AI briefing**
- Trader AI proactively suggests ideas that fit the user's coin balance and synced market data
- Trader AI question box handles common prompts such as best buy, what to sell, and biggest drops
- Portfolio uses the same full database search as the rest of the app
- Android notification support for:
  - Trader AI opportunities
  - large market moves
  - portfolio ROI targets
  - daily briefing
- Hourly background checks when Android allows them
- Existing v2.2 app data is preserved because v2.3 uses the same application ID and SharedPreferences

## Live data architecture

The Android APK does **not** contain a fixed list of promo cards. It loads `data/cards.json` from the GitHub repository via:

`https://raw.githubusercontent.com/TBJmaker/FCTA/main/data/cards.json`

That means the installed APK can receive new cards/promos and price changes without being rebuilt.

### Automatic market updater

This repo also includes `.github/workflows/refresh-market.yml` and `tools/sync_futbin.py`. The scheduled workflow attempts to refresh the FC27 catalogue and prices every six hours using the community-maintained `quick-fut-price` / `futbin-sdk` adapter and commits the new `data/cards.json` back to GitHub.

Important: this market adapter uses unofficial/reverse-engineered community web endpoints. It may change or stop working and should only be enabled where its use complies with the upstream site's terms. If a licensed/official data provider becomes available, only the updater/feed needs changing; the Android APK does not need a new promo-by-promo release.

The app never logs in to EA, never stores EA credentials and never places trades automatically.

## Build on GitHub

1. Upload the contents of this repo to `TBJmaker/FCTA`.
2. Open **Actions**.
3. Run or wait for **Build FC Trader Assistant v2.3 APK**.
4. Download the artifact named **FC-Trader-Assistant-v2.3-APK**.
5. Extract the ZIP and install `app-debug.apk` on Android.

The build workflow auto-detects the Android Gradle project, so extra folder nesting should not break the build.

## Market feed refresh

After upload, open **Actions → Refresh FC27 market feed** and run it manually once. If it succeeds, `data/cards.json` will be replaced with the fetched FC27 catalogue. After that, the schedule runs every six hours. If the upstream adapter fails, the existing feed is left in place and the Android app continues using its cached last-good data.

## Notes

- Android 13+ asks for notification permission.
- Background notification timing is controlled by Android and can be delayed by battery optimisation.
- Trading signals and Trader AI ideas are analysis tools, not guaranteed predictions.
- Profit calculations apply the standard 5% EA transfer-market tax.
