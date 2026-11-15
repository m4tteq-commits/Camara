from datetime import date, datetime, timedelta
from uuid import NAMESPACE_URL, uuid5
from zoneinfo import ZoneInfo

from .catalog import candidates, canonical
from .models import Recipe, RecipeRequest, Snapshot, UseSoonIdea


def use_soon(snapshot: Snapshot, today: date | None = None) -> list[UseSoonIdea]:
    today = today or datetime.now(ZoneInfo("Europe/Bucharest")).date()
    until = today + timedelta(days=3)
    available = [item for item in snapshot.pantry if item.expires_on is None or item.expires_on >= today]
    expiring = {}
    for item in sorted((item for item in available if item.expires_on and item.expires_on <= until), key=lambda item: item.expires_on):
        unit = {"kg": "g", "l": "ml"}.get(item.unit, item.unit)
        expiring.setdefault((canonical(item.name), unit), item)
    if not expiring:
        return []

    request = RecipeRequest(max_minutes=180, equipment=["plita", "tigaie", "oala", "cuptor", "microunde"])
    ranked = []
    for entry, draft in candidates(request, available, snapshot.personal_recipes, require_stock_overlap=False, limit=None):
        ingredient_keys = {(canonical(name), unit) for name, quantity, unit in entry.parts if quantity > 0 and canonical(name) != "apa"}
        matched = [item for key, item in expiring.items() if key in ingredient_keys]
        if not matched:
            continue
        order = (len(draft.missing_ingredients), matched[0].expires_on, -len(matched), draft.minutes, entry.id)
        recipe = Recipe(**draft.model_dump(), id=uuid5(NAMESPACE_URL, f"camara:use-soon:{entry.id}"),
                        model="Din cămară", generated_at=today.isoformat(), generation_ms=0)
        ranked.append((order, UseSoonIdea(recipe=recipe, foods=list(dict.fromkeys(item.name for item in matched)))))
    ranked.sort(key=lambda row: row[0])
    return [idea for _, idea in ranked[:8]]
