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
MARKET_URL = "https://fc27-market.val.run/latest"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
    "Accept": "application/json,text/plain,*/*",
    "Referer": "https://www.fut.gg/",
}
TIMEOUT = 30

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

def card_from_row(row, previous):
    ea_id = number(row, "eaId", "id")
    if not ea_id:
        return None
    cid = str(ea_id)
    old = previous.get(cid, {})
    name = text(row, "commonName", "cardName", "name", "firstName", default=f"Player {cid}")
    if text(row, "lastName") and name == text(row, "firstName"):
        name = (name + " " + text(row, "lastName")).strip()
    version = text(row, "rarityName", "rarity", default="Base")
    position = text(row, "position", "positionName")
    if not position:
        positions = row.get("positions")
        if isinstance(positions, list) and positions:
            p0 = positions[0]
            position = text(p0, "position", "name", "shortName") if isinstance(p0, dict) else str(p0)
    club = text(row, "club", "clubName")
    league = text(row, "league", "leagueName")
    nation = text(row, "nation", "nationName")
    return {
        "id": cid,
        "name": name,
        "version": version,
        "rating": number(row, "overall", "rating"),
        "position": position,
        "club": club,
        "league": league,
        "nation": nation,
        "imageUrl": text(row, "cardImageUrl", "imageUrl"),
        "pricePc": number(old, "pricePc"),
        "priceConsole": number(old, "priceConsole"),
        "trendPc": float(old.get("trendPc", 0.0) or 0.0),
        "trendConsole": float(old.get("trendConsole", 0.0) or 0.0),
    }

def crawl_catalogue(previous):
    cards = {}
    # Main catalogue. FUT.GG currently exposes 30 rows per page and may cap a broad query,
    # so we also crawl rarity buckets below to pick up special/promotional cards.
    page = 1
    while page <= 400:
        try:
            payload = fetch_json(META_URL, {"page": page})
        except requests.HTTPError as e:
            if e.response is not None and e.response.status_code in (404, 400):
                break
            raise
        rows = payload.get("data") or []
        if not rows:
            break
        for row in rows:
            c = card_from_row(row, previous)
            if c:
                cards[c["id"]] = c
        print(f"catalogue page {page}: {len(rows)} rows, total {len(cards)}")
        nxt = payload.get("next")
        if not nxt:
            break
        page += 1
        time.sleep(0.05)

    # Probe rarity buckets to improve promo/special coverage without rebuilding the APK.
    for rarity_id in range(0, 128):
        page = 1
        found_any = False
        while page <= 400:
            try:
                payload = fetch_json(META_URL, {"rarity_id": rarity_id, "page": page})
            except requests.HTTPError as e:
                if e.response is not None and e.response.status_code in (404, 400):
                    break
                raise
            rows = payload.get("data") or []
            if not rows:
                break
            found_any = True
            before = len(cards)
            for row in rows:
                c = card_from_row(row, previous)
                if c:
                    cards[c["id"]] = c
            if page == 1:
                print(f"rarity {rarity_id}: first page {len(rows)} rows")
            if not payload.get("next"):
                break
            page += 1
            time.sleep(0.05)
        if found_any:
            print(f"rarity {rarity_id}: total cards now {len(cards)}")
    return cards

def merge_market_prices(cards, previous):
    try:
        payload = fetch_json(MARKET_URL)
    except Exception as exc:
        print(f"Market price overlay unavailable: {exc}")
        return

    groups = payload.get("groups") or {}
    priced = 0
    for entries in groups.values():
        if not isinstance(entries, list):
            continue
        for p in entries:
            ea_id = p.get("eaId")
            if ea_id is None:
                continue
            cid = str(ea_id)
            price = int(p.get("price") or 0)
            if cid not in cards:
                cards[cid] = {
                    "id": cid,
                    "name": str(p.get("name") or f"Player {cid}"),
                    "version": "Market",
                    "rating": int(p.get("rating") or 0),
                    "position": "",
                    "club": "",
                    "league": "",
                    "nation": "",
                    "imageUrl": str(p.get("image") or ""),
                    "pricePc": 0,
                    "priceConsole": 0,
                    "trendPc": 0.0,
                    "trendConsole": 0.0,
                }
            old = previous.get(cid, {})
            old_console = int(old.get("priceConsole") or 0)
            cards[cid]["priceConsole"] = price
            # Prefer the public collector's own 7-day movement when available.
            movement = p.get("pct_7d")
            cards[cid]["trendConsole"] = round(float(movement), 2) if isinstance(movement, (int, float)) else pct(old_console, price)
            priced += 1
    print(f"Applied console prices to {priced} cards")

def main():
    previous = load_previous()
    cards = crawl_catalogue(previous)
    merge_market_prices(cards, previous)

    if len(cards) < 1000:
        raise SystemExit(f"Refusing to overwrite feed with only {len(cards)} cards")

    result = {
        "meta": {
            "version": 2,
            "game": "EA SPORTS FC 27",
            "updatedAt": now_iso(),
            "source": "FUT.GG catalogue + public FC27 console market collector",
            "notes": "Catalogue auto-refreshes; console price coverage comes from the public market collector. PC live-price coverage is not yet available from the verified source."
        },
        "cards": sorted(cards.values(), key=lambda c: (-int(c.get("rating") or 0), c.get("name") or "", c.get("version") or "")),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(result['cards'])} cards to {OUT}")

if __name__ == "__main__":
    main()
