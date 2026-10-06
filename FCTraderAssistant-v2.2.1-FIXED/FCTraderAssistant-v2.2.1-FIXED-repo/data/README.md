# Cloud card feed

`cards.json` is the native card feed used by FC Trader Assistant v2.1.

The installed Android app checks the raw GitHub copy of this file at startup (when Auto-sync is enabled) and caches the latest successful copy for offline use. Updating this JSON does **not** require rebuilding the APK.

Default URL used by the app:

`https://raw.githubusercontent.com/TBJmaker/FC-Trader-Assistant/main/data/cards.json`

If the GitHub username/repository changes, update the URL inside the app under **More → Cloud data URL**.

## Card shape

```json
{
  "id": "unique-card-id",
  "name": "Player Name",
  "version": "TOTW",
  "rating": 88,
  "position": "ST",
  "club": "Club",
  "league": "League",
  "nation": "Nation",
  "pricePc": 25000,
  "priceConsole": 23000,
  "trendPc": 4.2,
  "trendConsole": 2.8
}
```

The app also contains a **Live FC 27 database** view powered by FUT.GG so newly released cards can be looked up even before they are added to this native feed. Missing cards can be added manually in the app and will not be overwritten by cloud sync.
