#!/usr/bin/env python3
import json
import sys
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else "data/cards.json")
raw = json.loads(path.read_text(encoding="utf-8"))
cards = raw.get("cards", [])
if not isinstance(cards, list) or not cards:
    raise SystemExit("cards must be a non-empty array")

seen = set()
required = {"id", "name", "version", "rating", "position"}
for i, card in enumerate(cards):
    missing = required - card.keys()
    if missing:
        raise SystemExit(f"card #{i} missing: {', '.join(sorted(missing))}")
    if card["id"] in seen:
        raise SystemExit(f"duplicate id: {card['id']}")
    seen.add(card["id"])
    rating = int(card["rating"])
    if rating < 1 or rating > 99:
        raise SystemExit(f"invalid rating for {card['id']}: {rating}")

print(f"OK: {len(cards)} cards")
