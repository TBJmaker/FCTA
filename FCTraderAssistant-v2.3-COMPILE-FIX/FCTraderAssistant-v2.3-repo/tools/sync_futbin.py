#!/usr/bin/env python3
"""Experimental FC27 market-feed adapter.

This tool is intentionally separate from the Android app. It can populate data/cards.json
from the community-maintained `futbin-sdk` package, then the installed app simply reads the
GitHub raw JSON feed. That means new cards/promos can appear without rebuilding the APK.

The upstream SDK uses unofficial/reverse-engineered FUTBIN web endpoints. It can change or
stop working and you should only enable it if that use is acceptable to you and the upstream
site's terms. The Android app itself never logs into EA and never automates trading.
"""

from __future__ import annotations

import asyncio
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

OUT = Path(__file__).resolve().parents[1] / "data" / "cards.json"
YEAR = int(os.getenv("FC_YEAR", "27"))
MAX_PAGES = int(os.getenv("MAX_PAGES", "500"))


def value(obj: Any, *names: str, default=None):
    for name in names:
        if isinstance(obj, dict) and name in obj:
            v = obj.get(name)
        else:
            v = getattr(obj, name, None)
        if v is not None:
            return v
    return default


def text_value(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, str):
        return v
    if isinstance(v, dict):
        return str(v.get("name") or v.get("label") or v.get("title") or "")
    return str(getattr(v, "name", None) or getattr(v, "label", None) or v)


def int_value(v: Any) -> int:
    try:
        if v is None or v == "":
            return 0
        if isinstance(v, str):
            v = v.replace(",", "").replace(".", "") if "," in v else v
        return int(float(v))
    except Exception:
        return 0


def load_previous() -> dict[str, dict[str, Any]]:
    if not OUT.exists():
        return {}
    try:
        root = json.loads(OUT.read_text(encoding="utf-8"))
        return {str(c.get("id")): c for c in root.get("cards", []) if c.get("id")}
    except Exception:
        return {}


def pct_change(old: int, new: int) -> float:
    if old <= 0 or new <= 0:
        return 0.0
    return round((new - old) * 100.0 / old, 2)


async def fetch_catalogue():
    from futbin_sdk import FutbinClient, PlayerSearchOptions

    players: dict[str, Any] = {}
    async with FutbinClient(year=YEAR) as client:
        empty_pages = 0
        for page in range(1, MAX_PAGES + 1):
            options = PlayerSearchOptions(page=page)
            batch = await client.search_players(options=options)
            if not batch:
                empty_pages += 1
                if empty_pages >= 2:
                    break
                continue
            empty_pages = 0
            added = 0
            for p in batch:
                pid = value(p, "futbin_id", "id", "player_id", "resource_id")
                if pid is None:
                    continue
                key = str(pid)
                if key not in players:
                    players[key] = p
                    added += 1
            print(f"page {page}: {len(batch)} rows, {added} new, total {len(players)}")
            if added == 0 and page > 2:
                break
            await asyncio.sleep(0.35)

        # Latest is cheap and helps pick up newly released promo cards quickly.
        try:
            latest = await client.get_latest_players()
            for p in latest or []:
                pid = value(p, "futbin_id", "id", "player_id", "resource_id")
                if pid is not None:
                    players[str(pid)] = p
        except Exception as exc:
            print(f"latest-player refresh skipped: {exc}")

    return players


async def main():
    previous = load_previous()
    raw = await fetch_catalogue()
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    cards = []

    for pid, p in raw.items():
        name = text_value(value(p, "name", "player_name", "display_name")).strip()
        if not name:
            continue
        version = text_value(value(p, "version", "version_name", "card_version", "rarity", default="Base")).strip() or "Base"
        rating = int_value(value(p, "rating", "overall"))
        position = text_value(value(p, "position", "position_name")).strip().upper()
        club = text_value(value(p, "club", "club_name")).strip()
        league = text_value(value(p, "league", "league_name")).strip()
        nation = text_value(value(p, "nation", "nation_name")).strip()

        pc = int_value(value(p, "price_pc", "pc_price", "pricePc"))
        console = int_value(value(p, "price_ps", "ps_price", "price_console", "console_price", "priceConsole"))

        old = previous.get(pid, {})
        trend_pc = pct_change(int_value(old.get("pricePc")), pc)
        trend_console = pct_change(int_value(old.get("priceConsole")), console)

        cards.append({
            "id": pid,
            "name": name,
            "version": version,
            "rating": rating,
            "position": position,
            "club": club,
            "league": league,
            "nation": nation,
            "pricePc": pc,
            "priceConsole": console,
            "trendPc": trend_pc,
            "trendConsole": trend_console,
            "source": "FUTBIN community web adapter",
            "updatedAt": now,
        })

    if not cards:
        raise RuntimeError("Updater returned zero cards; existing feed was left untouched")

    cards.sort(key=lambda c: (-int(c.get("rating", 0)), c.get("name", ""), c.get("version", "")))
    payload = {
        "meta": {
            "version": 3,
            "game": "EA SPORTS FC 27",
            "updatedAt": now,
            "source": "FC Trader Assistant market feed",
        },
        "cards": cards,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(cards)} cards to {OUT}")


if __name__ == "__main__":
    asyncio.run(main())
