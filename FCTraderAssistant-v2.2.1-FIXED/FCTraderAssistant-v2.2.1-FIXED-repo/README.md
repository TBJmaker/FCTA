# FC Trader Assistant v2.2

A manual EA SPORTS FC trading companion for Android.

## v2.2 highlights
- Predictive player/card search on Market and Portfolio.
- Market page simplified to percentage movement + BUY / SELL / HOLD.
- Target buy and target sell prices shown on each market card.
- Biggest Risers and Biggest Fallers sections.
- Best Buys and Best Sells filters.
- Cleaner dark trading UI with brighter market accents.
- New FC Trader Assistant app icon.
- Existing balance, portfolio, watchlist, analytics, cloud card feed, backup and 5% EA tax logic retained.

## Important
BUY / SELL / HOLD is a simple trading signal based on the synced percentage trend, not a guarantee. Target prices use the user's target ROI and the 5% EA tax.

## GitHub build
The included `.github/workflows/build-apk.yml` builds a debug APK on every push to `main`. Download the resulting `FC-Trader-Assistant-APK` artifact from GitHub Actions.


## Fresh-build package
This package intentionally uses the Android project folder `FCTraderAssistantV22` and application ID `com.tbjmaker.fctraderassistant.v22`. It installs alongside the old app so you can immediately tell whether the v2.2 source was built. The GitHub Action in this package builds only `FCTraderAssistantV22`.
