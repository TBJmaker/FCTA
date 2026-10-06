# Market feed

`cards.json` is the remote database consumed by the Android app. Each card version is a separate record.

Required fields: `id`, `name`, `version`, `rating`, `position`.

Recommended fields: `club`, `league`, `nation`, `pricePc`, `priceConsole`, `trendPc`, `trendConsole`, `source`, `updatedAt`.

The v2.3 app treats `trendPc` / `trendConsole` as the recent signed percentage move supplied by the feed. The bundled updater computes this from the previous feed snapshot when an upstream source does not publish a directly usable trend.
