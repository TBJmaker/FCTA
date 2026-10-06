#!/usr/bin/env python3
from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "cards.json"

META_URL = "https://www.fut.gg/api/fut/players/v2/27/"
PS5_PRICE_URL = "https://s3.eu-west-2.amazonaws.com/game-assets.fut.gg/27/cdn-data/player-prices-ps5.json"
PC_PRICE_CANDIDATES = [
    "https://s3.eu-west-2.amazonaws.com/game-assets.fut.gg/27/cdn-data/player-prices-pc.json",
    "https://s3.eu-west-2.amazonaws.com/game-assets.fut.gg/27/cdn-data/player-prices-pc5.json",
]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Referer": "https://www.fut.gg/",
}
TIMEOUT = 30
BATCH_SIZE = 30


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_previous():
    if not OUT.exists():
        return {}
    try:
        root = json.loads(OUT.read_text(encoding="utf-8"))
        return {str(c.get("id")): c for c in root.get("cards", []) if c.get("id")}
    except Exception:
        return {}


def text(obj, *keys, default=""):
    for key in keys:
        v = obj.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip()
        if isinstance(v, dict):
            for k in ("name", "label", "title", "shortName"):
                vv = v.get(k)
                if isinstance(vv, str) and vv.strip():
                    return vv.strip()
    return default


def number(obj, *keys, default=0):
    for key in keys:
        v = obj.get(key)
        if isinstance(v, (int, float)):
            return int(v)
        if isinstance(v, str):
            try:
                return int(float(v.replace(",", "")))
            except Exception:
                pass
    return default


def pct(old: int, new: int) -> float:
    if old <= 0 or new <= 0:
        return 0.0
    return round((new - old) * 100.0 / old, 2)


def fetch_json(url, params=None):
    r = requests.get(url, params=params, headers=HEADERS, timeout=TIMEOUT)
    r.raise_for_status()
    return r.json()


def decode_compact_prices(url: str) -> dict[str, int]:
    payload = fetch_json(url)
    id0 = int(payload["id0"])
    gaps = payload.get("d") or []
    prices = payload.get("p") or []
    ids = [id0]
    for gap in gaps:
        ids.append(ids[-1] + int(gap))
    if len(ids) != len(prices):
        raise RuntimeError(f"Compact price feed mismatch: {len(ids)} ids vs {len(prices)} prices")
    return {str(pid): int(price or 0) for pid, price in zip(ids, prices)}


def load_pc_prices() -> tuple[dict[str, int], str]:
    for url in PC_PRICE_CANDIDATES:
        try:
            prices = decode_compact_prices(url)
            if len(prices) > 1000:
                print(f"PC price feed found: {url} ({len(prices)} cards)")
                return prices, url
        except Exception as exc:
            print(f"PC price candidate unavailable: {url} -> {exc}")
    return {}, ""


def position_text(row: dict) -> str:
    value = text(
        row,
        "position",
        "positionName",
        "primaryPosition",
        "preferredPosition",
        "positionShortName",
    )
    if value:
        return value.upper()
    for key in ("positions", "positionNames", "alternatePositions"):
        vals = row.get(key)
        if isinstance(vals, list) and vals:
            first = vals[0]
            if isinstance(first, dict):
                value = text(first, "position", "name", "shortName", "label")
            else:
                value = str(first)
            if value:
                return value.upper()
    return ""


def card_from_row(row: dict, previous: dict[str, dict]):
    ea_id = number(row, "eaId", "id")
    if not ea_id:
        return None
    cid = str(ea_id)
    old = previous.get(cid, {})

    name = text(row, "commonName", "cardName", "name", "nickname", "firstName", default=f"Player {cid}")
    if name == text(row, "firstName") and text(row, "lastName"):
        name = (name + " " + text(row, "lastName")).strip()

    return {
        "id": cid,
        "name": name,
        "version": text(row, "rarityName", "rarity", "cardType", default="Base"),
        "rating": number(row, "overall", "rating"),
        "position": position_text(row),
        "club": text(row, "club", "uniqueClub", "clubName"),
        "league": text(row, "league", "leagueName"),
        "nation": text(row, "nation", "nationName"),
        "imageUrl": text(row, "cardImageUrl", "imageUrl"),
        "pricePc": number(old, "pricePc"),
        "priceConsole": number(old, "priceConsole"),
        "trendPc": float(old.get("trendPc", 0.0) or 0.0),
        "trendConsole": float(old.get("trendConsole", 0.0) or 0.0),
    }


def fetch_metadata_for_ids(ids: list[str], previous: dict[str, dict]) -> dict[str, dict]:
    cards: dict[str, dict] = {}
    total_batches = (len(ids) + BATCH_SIZE - 1) // BATCH_SIZE
    for n in range(0, len(ids), BATCH_SIZE):
        batch = ids[n:n + BATCH_SIZE]
        try:
            payload = fetch_json(META_URL, {"ids": ",".join(batch)})
        except Exception as exc:
            print(f"metadata batch {n // BATCH_SIZE + 1}/{total_batches} failed: {exc}")
            continue
        for row in payload.get("data") or []:
            card = card_from_row(row, previous)
            if card:
                cards[card["id"]] = card
        if (n // BATCH_SIZE + 1) % 25 == 0:
            print(f"metadata batches {n // BATCH_SIZE + 1}/{total_batches}: {len(cards)} cards")
        time.sleep(0.03)
    return cards


def crawl_public_catalogue(cards: dict[str, dict], previous: dict[str, dict]):
    # Also crawl the normal catalogue so newly released/unpriced promo items can
    # appear before they reach either compact price file.
    page = 1
    while page <= 400:
        try:
            payload = fetch_json(META_URL, {"page": page})
        except requests.HTTPError as exc:
            if exc.response is not None and exc.response.status_code in (400, 404):
                break
            raise
        rows = payload.get("data") or []
        if not rows:
            break
        for row in rows:
            card = card_from_row(row, previous)
            if card:
                cards[card["id"]] = card
        if page % 25 == 0:
            print(f"catalogue page {page}: total metadata {len(cards)}")
        if not payload.get("next"):
            break
        page += 1
        time.sleep(0.03)


def apply_prices(cards: dict[str, dict], previous: dict[str, dict], field: str, trend_field: str, prices: dict[str, int]):
    applied = 0
    for cid, price in prices.items():
        if cid not in cards:
            old = previous.get(cid, {})
            cards[cid] = {
                "id": cid,
                "name": old.get("name") or f"Player {cid}",
                "version": old.get("version") or "Unknown",
                "rating": int(old.get("rating") or 0),
                "position": old.get("position") or "",
                "club": old.get("club") or "",
                "league": old.get("league") or "",
                "nation": old.get("nation") or "",
                "imageUrl": old.get("imageUrl") or "",
                "pricePc": int(old.get("pricePc") or 0),
                "priceConsole": int(old.get("priceConsole") or 0),
                "trendPc": float(old.get("trendPc") or 0.0),
                "trendConsole": float(old.get("trendConsole") or 0.0),
            }
        old_price = int(previous.get(cid, {}).get(field) or 0)
        cards[cid][field] = int(price or 0)
        cards[cid][trend_field] = pct(old_price, int(price or 0))
        if price:
            applied += 1
    return applied


def main():
    previous = load_previous()

    console_prices = decode_compact_prices(PS5_PRICE_URL)
    print(f"Console compact price feed: {len(console_prices)} cards")

    pc_prices, pc_source = load_pc_prices()
    if pc_prices:
        print(f"PC compact price feed: {len(pc_prices)} cards")
    else:
        print("No verified public PC compact price file was found; previous PC values will be preserved.")

    master_ids = sorted(set(console_prices) | set(pc_prices), key=lambda x: int(x))
    print(f"Master priced-card id set: {len(master_ids)}")

    cards = fetch_metadata_for_ids(master_ids, previous)
    crawl_public_catalogue(cards, previous)

    console_applied = apply_prices(cards, previous, "priceConsole", "trendConsole", console_prices)
    pc_applied = apply_prices(cards, previous, "pricePc", "trendPc", pc_prices) if pc_prices else 0

    # Preserve any previous PC prices when a live PC source is unavailable.
    if not pc_prices:
        for cid, card in cards.items():
            old = previous.get(cid, {})
            card["pricePc"] = int(old.get("pricePc") or card.get("pricePc") or 0)
            card["trendPc"] = float(old.get("trendPc") or card.get("trendPc") or 0.0)

    if len(cards) < 1000:
        raise SystemExit(f"Refusing to overwrite feed with only {len(cards)} cards")

    result = {
        "meta": {
            "version": 3,
            "game": "EA SPORTS FC 27",
            "updatedAt": now_iso(),
            "source": "FUT.GG catalogue + FUT.GG compact market feeds",
            "consolePricedCards": console_applied,
            "pcPricedCards": pc_applied,
            "pcPriceSource": pc_source,
        },
        "cards": sorted(
            cards.values(),
            key=lambda c: (-int(c.get("rating") or 0), c.get("name") or "", c.get("version") or "")
        ),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(result['cards'])} cards to {OUT}")
    print(f"Priced cards: console={console_applied}, pc={pc_applied}")


if __name__ == "__main__":
    main()
