#!/usr/bin/env python3
"""Build data/cards.json from FUTWIZ's public FC27 player catalogue.

This is an unofficial web adapter. It does not log into EA, buy/sell items, or
use an EA account. FUTWIZ can change its HTML or block automated requests at
any time, so failures leave the existing feed untouched.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable
from urllib.parse import urljoin

import cloudscraper
from bs4 import BeautifulSoup, Tag

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "cards.json"
BASE = "https://www.futwiz.com"
LIST_URL = os.getenv("FUTWIZ_LIST_URL", f"{BASE}/eafc27/players/latest")
MAX_PAGES = int(os.getenv("MAX_PAGES", "650"))
REQUEST_DELAY = float(os.getenv("REQUEST_DELAY", "0.65"))
TIMEOUT = int(os.getenv("HTTP_TIMEOUT", "35"))
MAX_RETRIES = int(os.getenv("HTTP_RETRIES", "4"))

PLAYER_HREF_RE = re.compile(r"/eafc27/player/[^?#]+/\d+/?$")
RATING_POS_RE = re.compile(r"^\s*(\d{2})\s+([A-Z]{1,4})\b")
ADDED_RE = re.compile(r"\bAdded:\s*\d{1,2}/\d{1,2}/\d{2,4}\b", re.I)
COIN_RE = re.compile(r"^(?:\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?[KMB])$", re.I)


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def coin_value(token: str) -> int:
    s = token.strip().upper().replace(",", "")
    if not s:
        return 0
    mult = 1
    if s[-1:] in {"K", "M", "B"}:
        mult = {"K": 1_000, "M": 1_000_000, "B": 1_000_000_000}[s[-1]]
        s = s[:-1]
    try:
        return int(round(float(s) * mult))
    except ValueError:
        return 0


def pct_change(old: int, new: int) -> float:
    if old <= 0 or new <= 0:
        return 0.0
    return round((new - old) * 100.0 / old, 2)


def load_previous() -> dict[str, dict]:
    if not OUT.exists():
        return {}
    try:
        root = json.loads(OUT.read_text(encoding="utf-8"))
        return {str(c.get("id")): c for c in root.get("cards", []) if c.get("id")}
    except Exception:
        return {}


def make_scraper():
    scraper = cloudscraper.create_scraper(
        browser={"browser": "chrome", "platform": "windows", "mobile": False},
        delay=5,
    )
    scraper.headers.update(
        {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-GB,en;q=0.9",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "Referer": BASE + "/",
            "Upgrade-Insecure-Requests": "1",
        }
    )
    return scraper


def fetch(scraper, url: str) -> str:
    last = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            r = scraper.get(url, timeout=TIMEOUT)
            if r.status_code == 200 and len(r.text) > 1_000:
                return r.text
            last = RuntimeError(f"HTTP {r.status_code} from {url}")
            if r.status_code in {403, 429, 503}:
                time.sleep(min(20, 3 * attempt))
                continue
            r.raise_for_status()
        except Exception as exc:
            last = exc
            time.sleep(min(20, 3 * attempt))
    raise RuntimeError(f"Unable to fetch {url}: {last}")


def attr_text(node: Tag) -> str:
    bits: list[str] = []
    for key in ("title", "alt", "aria-label", "data-name", "data-title"):
        v = node.get(key)
        if isinstance(v, str) and v.strip():
            bits.append(v.strip())
    img = node.find("img")
    if img:
        for key in ("alt", "title"):
            v = img.get(key)
            if isinstance(v, str) and v.strip():
                bits.append(v.strip())
    txt = node.get_text(" ", strip=True)
    if txt:
        bits.append(txt)
    # Preserve order, remove duplicates.
    return " ".join(dict.fromkeys(bits))


def linked_label(container: Tag, kind: str) -> str:
    # FUTWIZ has historically linked nation/league/club badges. Handle several
    # URL forms and use text/title/alt so this survives cosmetic HTML changes.
    for a in container.find_all("a", href=True):
        href = str(a.get("href") or "").lower()
        if f"/{kind}/" in href or f"/{kind}s/" in href:
            value = attr_text(a).strip()
            if value:
                return value
    # Fall back to data attributes/classes if the current template does not link badges.
    for node in container.find_all(True):
        cls = " ".join(node.get("class") or []).lower()
        attrs = " ".join(str(k).lower() for k in node.attrs.keys())
        if kind in cls or kind in attrs:
            value = attr_text(node).strip()
            if value and len(value) < 100:
                return value
    return ""


def version_label(container: Tag) -> str:
    # Check explicit version/rarity data first.
    for node in [container, *container.find_all(True)]:
        for key in ("data-version", "data-rarity", "data-card", "data-card-version"):
            v = node.get(key)
            if isinstance(v, str) and v.strip():
                return v.strip()
    # Card art often carries its rarity/version in alt/title.
    candidates: list[str] = []
    for img in container.find_all("img"):
        for key in ("alt", "title"):
            v = img.get(key)
            if isinstance(v, str) and v.strip():
                candidates.append(v.strip())
    keywords = (
        "icon", "hero", "inform", "team of the week", "totw", "toty", "tots",
        "destined", "holographic", "promo", "special", "rare", "common",
        "road to", "future", "trailblazer", "evolution", "evo", "ucl", "uwcl",
    )
    for c in candidates:
        low = c.lower()
        if any(k in low for k in keywords) and len(c) <= 80:
            return c
    return "Base"


def price_pair(row_text: str) -> tuple[int, int]:
    """Return (console, pc) from the tail of a FUTWIZ search row.

    Rows currently end roughly as: `... Right 2,625 80.5K 82.5K Added: ...`.
    The first number after foot is an item score; the final two values are the
    console and PC market prices. Cards without prices have only the item score.
    """
    before_added = ADDED_RE.split(row_text, maxsplit=1)[0]
    m = re.search(r"\b(?:right|left)\b\s+(.+)$", before_added, re.I)
    tail = m.group(1) if m else before_added
    tokens = [t.strip("()[]{}:;") for t in tail.split()]
    numeric = [t for t in tokens if COIN_RE.match(t)]
    if len(numeric) >= 3:
        return coin_value(numeric[-2]), coin_value(numeric[-1])
    # Some templates omit the score. If the final two tokens use K/M/B notation,
    # they are safely identifiable as prices.
    if len(numeric) >= 2 and any(ch in numeric[-2].upper() for ch in "KMB"):
        return coin_value(numeric[-2]), coin_value(numeric[-1])
    return 0, 0


def player_container(anchor: Tag) -> Tag:
    row = anchor.find_parent("tr")
    if row:
        return row
    # Newer responsive layouts may use cards instead of table rows. Walk up to
    # the smallest sensible container that contains one player link.
    node = anchor
    for _ in range(7):
        parent = node.parent
        if not isinstance(parent, Tag):
            break
        player_links = parent.find_all("a", href=PLAYER_HREF_RE)
        if len(player_links) == 1 and len(parent.get_text(" ", strip=True)) > 20:
            node = parent
        else:
            break
    return node


def parse_page(html: str) -> list[dict]:
    soup = BeautifulSoup(html, "lxml")
    anchors = []
    seen_href = set()
    for a in soup.find_all("a", href=True):
        href = str(a.get("href") or "")
        path = href.split("?", 1)[0].rstrip("/")
        if PLAYER_HREF_RE.search(path) and path not in seen_href:
            seen_href.add(path)
            anchors.append(a)

    cards: list[dict] = []
    for a in anchors:
        href = str(a.get("href") or "")
        pid_match = re.search(r"/(\d+)/?$", href.split("?", 1)[0])
        if not pid_match:
            continue
        pid = pid_match.group(1)
        name = a.get_text(" ", strip=True)
        if not name:
            name = str(a.get("title") or "").strip()
        if not name:
            img = a.find("img")
            name = str(img.get("alt") or "").strip() if img else ""
        if not name:
            continue

        container = player_container(a)
        text = " ".join(container.get_text(" ", strip=True).split())
        rp = RATING_POS_RE.search(text)
        rating = int(rp.group(1)) if rp else 0
        position = rp.group(2).upper() if rp else ""
        console, pc = price_pair(text)

        cards.append(
            {
                "id": pid,
                "name": name,
                "version": version_label(container),
                "rating": rating,
                "position": position,
                "club": linked_label(container, "club"),
                "league": linked_label(container, "league"),
                "nation": linked_label(container, "nation"),
                "pricePc": pc,
                "priceConsole": console,
                "sourceUrl": urljoin(BASE, href),
            }
        )
    return cards


def page_url(page: int) -> str:
    sep = "&" if "?" in LIST_URL else "?"
    return f"{LIST_URL}{sep}page={page}"


def main() -> int:
    previous = load_previous()
    scraper = make_scraper()
    all_cards: dict[str, dict] = {}
    zero_pages = 0

    for page in range(1, MAX_PAGES + 1):
        url = page_url(page)
        html = fetch(scraper, url)
        batch = parse_page(html)
        if not batch:
            zero_pages += 1
            print(f"page {page}: no player rows")
            if zero_pages >= 2:
                break
        else:
            zero_pages = 0
            new_count = 0
            for card in batch:
                if card["id"] not in all_cards:
                    new_count += 1
                all_cards[card["id"]] = card
            print(f"page {page}: {len(batch)} rows, {new_count} new, total {len(all_cards)}")

        # Stop when the page itself says we are at the last page.
        m = re.search(r"Page\s+(\d+)\s+of\s+(\d+)", BeautifulSoup(html, "lxml").get_text(" ", strip=True), re.I)
        if m and int(m.group(1)) >= int(m.group(2)):
            break
        time.sleep(REQUEST_DELAY)

    if len(all_cards) < 1_000:
        raise RuntimeError(
            f"FUTWIZ returned only {len(all_cards)} cards; refusing to overwrite the existing feed"
        )

    stamp = now_iso()
    cards = []
    for pid, card in all_cards.items():
        old = previous.get(pid, {})
        pc = int(card.get("pricePc") or 0)
        console = int(card.get("priceConsole") or 0)
        cards.append(
            {
                "id": pid,
                "name": card.get("name", ""),
                "version": card.get("version", "Base") or "Base",
                "rating": int(card.get("rating") or 0),
                "position": card.get("position", ""),
                "club": card.get("club", ""),
                "league": card.get("league", ""),
                "nation": card.get("nation", ""),
                "pricePc": pc,
                "priceConsole": console,
                "trendPc": pct_change(int(old.get("pricePc") or 0), pc),
                "trendConsole": pct_change(int(old.get("priceConsole") or 0), console),
                "source": "FUTWIZ public FC27 catalogue",
                "updatedAt": stamp,
            }
        )

    cards.sort(key=lambda c: (-c["rating"], c["name"].casefold(), c["version"].casefold()))
    payload = {
        "meta": {
            "version": 3,
            "game": "EA SPORTS FC 27",
            "updatedAt": stamp,
            "source": "FUTWIZ public FC27 catalogue",
            "cardCount": len(cards),
        },
        "cards": cards,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(OUT)
    print(f"wrote {len(cards)} cards to {OUT}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
