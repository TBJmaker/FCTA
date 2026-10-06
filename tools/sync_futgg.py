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
PC_PRICE_URL = "https://www.fut.gg/api/fut/player-prices/27/"
PS5_ID_FEED = "https://s3.eu-west-2.amazonaws.com/game-assets.fut.gg/27/cdn-data/player-prices-ps5.json"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Referer": "https://www.fut.gg/",
    "X-Requested-With": "XMLHttpRequest",
}
TIMEOUT = 35
BATCH_SIZE = 30
MIN_PC_PRICES = 1000


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_previous() -> dict[str, dict]:
    if not OUT.exists():
        return {}
    try:
        root = json.loads(OUT.read_text(encoding="utf-8"))
        return {str(c.get("id")): c for c in root.get("cards", []) if c.get("id")}
    except Exception:
        return {}


def text(obj: dict, *keys: str, default: str = "") -> str:
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


def number(obj: dict, *keys: str, default: int = 0) -> int:
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


def fetch_json(url: str, params=None):
    last = None
    for attempt in range(1, 4):
        try:
            r = requests.get(url, params=params, headers=HEADERS, timeout=TIMEOUT)
            r.raise_for_status()
            return r.json()
        except Exception as exc:
            last = exc
            if attempt < 3:
                time.sleep(attempt * 1.5)
    raise RuntimeError(f"Request failed for {url}: {last}")


def decode_compact_ids() -> list[str]:
    payload = fetch_json(PS5_ID_FEED)
    id0 = int(payload["id0"])
    gaps = payload.get("d") or []
    ids = [id0]
    for gap in gaps:
        ids.append(ids[-1] + int(gap))
    return [str(x) for x in ids]


def fetch_pc_prices(ids: list[str]) -> dict[str, int]:
    prices: dict[str, int] = {}
    total_batches = (len(ids) + BATCH_SIZE - 1) // BATCH_SIZE

    for start in range(0, len(ids), BATCH_SIZE):
        batch = ids[start:start + BATCH_SIZE]
        payload = fetch_json(
            PC_PRICE_URL,
            {"ids": ",".join(batch), "platform": "pc"},
        )
        data = payload.get("data") or []
        rows = data.values() if isinstance(data, dict) else data

        for row in rows:
            if not isinstance(row, dict):
                continue
            ea_id = number(row, "eaId", "id")
            if not ea_id:
                continue
            price = number(row, "price", "currentPrice", "lowestPrice")
            if price > 0:
                prices[str(ea_id)] = price

        batch_no = start // BATCH_SIZE + 1
        if batch_no == 1 or batch_no % 25 == 0 or batch_no == total_batches:
            print(f"PC price batches {batch_no}/{total_batches}: {len(prices)} live prices")
        time.sleep(0.05)

    return prices


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

    name = text(
        row,
        "commonName",
        "cardName",
        "name",
        "nickname",
        "firstName",
        default=f"Player {cid}",
    )
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

    for start in range(0, len(ids), BATCH_SIZE):
        batch = ids[start:start + BATCH_SIZE]
        try:
            payload = fetch_json(META_URL, {"ids": ",".join(batch)})
        except Exception as exc:
            print(f"metadata batch {start // BATCH_SIZE + 1}/{total_batches} failed: {exc}")
            continue

        for row in payload.get("data") or []:
            card = card_from_row(row, previous)
            if card:
                cards[card["id"]] = card

        batch_no = start // BATCH_SIZE + 1
        if batch_no == 1 or batch_no % 25 == 0 or batch_no == total_batches:
            print(f"metadata batches {batch_no}/{total_batches}: {len(cards)} cards")
        time.sleep(0.04)

    return cards


def crawl_public_catalogue(cards: dict[str, dict], previous: dict[str, dict]):
    # Adds newly released/unpriced items that may not yet exist in the market id feed.
    page = 1
    while page <= 400:
        try:
            payload = fetch_json(META_URL, {"page": page})
        except requests.HTTPError as exc:
            if exc.response is not None and exc.response.status_code in (400, 404):
                break
            raise
        except Exception as exc:
            print(f"catalogue crawl stopped at page {page}: {exc}")
            break

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
        time.sleep(0.04)


def apply_pc_prices(cards: dict[str, dict], previous: dict[str, dict], prices: dict[str, int]) -> int:
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
                "pricePc": 0,
                "priceConsole": int(old.get("priceConsole") or 0),
                "trendPc": 0.0,
                "trendConsole": float(old.get("trendConsole") or 0.0),
            }

        old_price = int(previous.get(cid, {}).get("pricePc") or 0)
        cards[cid]["pricePc"] = int(price)
        cards[cid]["trendPc"] = pct(old_price, int(price))
        applied += 1

    return applied


def main():
    previous = load_previous()

    master_ids = decode_compact_ids()
    print(f"Master FC27 market id set: {len(master_ids)} cards")

    pc_prices = fetch_pc_prices(master_ids)
    print(f"Live PC prices received: {len(pc_prices)}")

    # Never replace a good feed with an empty/blocked PC response.
    if len(pc_prices) < MIN_PC_PRICES:
        raise SystemExit(
            f"Only {len(pc_prices)} live PC prices were returned; "
            "refusing to overwrite the existing feed."
        )

    cards = fetch_metadata_for_ids(master_ids, previous)
    crawl_public_catalogue(cards, previous)

    pc_applied = apply_pc_prices(cards, previous, pc_prices)

    if len(cards) < 1000:
        raise SystemExit(f"Refusing to overwrite feed with only {len(cards)} cards")

    result = {
        "meta": {
            "version": 3,
            "game": "EA SPORTS FC 27",
            "updatedAt": now_iso(),
            "source": "FUT.GG FC27 catalogue + FUT.GG PC market prices",
            "platform": "PC",
            "livePriceCount": pc_applied,
        },
        "cards": sorted(
            cards.values(),
            key=lambda c: (
                -int(c.get("rating") or 0),
                c.get("name") or "",
                c.get("version") or "",
            ),
        ),
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(result, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"Wrote {len(result['cards'])} cards with {pc_applied} live PC prices to {OUT}")


if __name__ == "__main__":
    main()
