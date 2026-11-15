from datetime import date, timedelta
import json
from uuid import uuid4

import httpx
from test_api import revision
import pytest
from fastapi.testclient import TestClient

from app.ai import OllamaProvider
from app.bulk import preview
from app.catalog import ANIMAL, DAIRY_EGGS, CATALOG, candidates, canonical
from app.database import Database
from app.main import create_app
from app.models import PantryItem, RecipeRequest

KEY = "v02-test-" + "x" * 40
HEADERS = {"Authorization": "Bearer " + KEY}


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path / "v02.db", KEY), headers=HEADERS) as value:
        yield value


@pytest.fixture
def anyio_backend():
    return "asyncio"


def test_bulk_romanian_quantities_and_dates():
    rows = preview("6 ouă, 1 litru de lapte și 500 g de paste; două kg cartofi\nIaurt expiră 2026-11-05").rows
    assert [(r.item.name, r.item.quantity, r.item.unit) for r in rows[:4]] == [("Ouă", 6, "buc"), ("Lapte", 1, "l"), ("Paste", 500, "g"), ("Cartofi", 2, "kg")]
    assert rows[2].item.location == "camara"
    assert rows[-1].item.expires_on == date(2026, 11, 5)
    assert rows[-1].warnings


def test_bulk_decimal_comma_and_unknown_package_are_visible():
    rows = preview("0,5 kg de mere, 2 pachete de paste, ton").rows
    assert rows[0].item.quantity == 0.5
    assert rows[1].item.unit == "buc" and rows[1].warnings
    assert rows[2].item.quantity == 1 and rows[2].warnings


def test_bulk_keeps_unquantified_food_names_and_supports_suffixes():
    rows = preview("ouă, orez, unt, lapte 1l, 500 de grame de paste, 1/2 kg mere").rows
    assert [r.item.name for r in rows[:3]] == ["Ouă", "Orez", "Unt"]
    assert all(r.warnings for r in rows[:3])
    assert (rows[3].item.name, rows[3].item.quantity, rows[3].item.unit) == ("Lapte", 1, "l")
    assert rows[4].item.unit == "g"
    assert rows[5].item.quantity == 0.5


def test_preview_never_saves_and_batch_is_atomic_idempotent(client):
    parsed = client.post("/v1/pantry/preview", json={"text": "6 ouă, 1 litru lapte"})
    assert parsed.status_code == 200
    assert client.get("/v1/snapshot").json()["pantry"] == []
    items = [{"id": str(uuid4()), **r["item"]} for r in parsed.json()["rows"]]
    url = f"/v1/pantry/batches/{uuid4()}"
    assert client.put(url, json={"items": items}).json() == {"count": 2, "replayed": False}
    item = items[0]
    edited = {k: v for k, v in item.items() if k != "id"}
    edited["quantity"] = 3
    path = f"/v1/pantry/{item['id']}"
    assert client.put(path, json=edited, headers=revision(client, path)).status_code == 200
    assert client.put(url, json={"items": items}).json()["replayed"]
    assert next(p for p in client.get("/v1/snapshot").json()["pantry"] if p["id"] == item["id"])["quantity"] == 3
    items[0]["quantity"] = 10
    assert client.put(url, json={"items": items}).status_code == 409


def test_conflicting_batch_rolls_back_all_new_rows(client):
    existing = {"id": str(uuid4()), "name": "Ouă", "quantity": 1, "unit": "buc"}
    client.put(f"/v1/pantry/{existing['id']}", json={k: v for k, v in existing.items() if k != "id"})
    new_item = {**existing, "id": str(uuid4()), "name": "Paste"}
    response = client.put(f"/v1/pantry/batches/{uuid4()}", json={"items": [new_item, existing]})
    assert response.status_code == 409
    assert [p["id"] for p in client.get("/v1/snapshot").json()["pantry"]] == [existing["id"]]


def test_bulk_preview_rejects_quantity_beyond_float_range(client):
    assert client.post('/v1/pantry/preview', json={'text': '9' * 320 + ' g Orez'}).status_code == 422


def test_hard_filters_are_applied_before_ai():
    pantry = [PantryItem(id=uuid4(), name=part[0], quantity=10000, unit=part[2]) for recipe in CATALOG for part in recipe.parts]
    for diet in ("vegetarian", "vegan"):
        options = candidates(RecipeRequest(max_minutes=90, equipment=["plita", "tigaie", "oala", "microunde", "cuptor"], diet=diet, pantry_only=True), pantry)
        assert options
        for entry, recipe in options:
            names = {canonical(p[0]) for p in entry.parts}
            assert not names.intersection(ANIMAL)
            if diet == "vegan":
                assert not names.intersection(DAIRY_EGGS)
            assert not recipe.missing_ingredients


def test_device_choice_revalidates_inventory_and_does_not_accept_new_steps(client):
    item_id = str(uuid4())
    client.put(f"/v1/pantry/{item_id}", json={"name": "Ouă", "quantity": 6})
    options = client.post("/v1/recipes/options", json={}).json()
    choice = {"request": {}, "fingerprint": options["fingerprint"], "recipe_id": options["options"][0]["id"], "id": str(uuid4()), "generation_ms": 200}
    assert client.post("/v1/recipes/choose", json={**choice, "steps": ["invented"]}).status_code == 422
    assert client.post("/v1/recipes/choose", json=choice).status_code == 200
    assert client.post("/v1/recipes/choose", json=choice).status_code == 200
    assert len(client.get("/v1/snapshot").json()["recipes"]) == 1
    path = f"/v1/pantry/{item_id}"
    client.put(path, json={"name": "Ouă", "quantity": 3}, headers=revision(client, path)).raise_for_status()
    assert client.post("/v1/recipes/choose", json=choice).status_code == 409


@pytest.mark.anyio
async def test_selection_cache_reuses_the_same_prompt_and_invalidates_changes(tmp_path):
    calls = 0
    def respond(request):
        nonlocal calls
        calls += 1
        body = json.loads(request.content)
        assert len(body["prompt"]) < 1800
        return httpx.Response(200, json={"done": True, "response": '{"recipe_id":"omleta"}'})
    db = Database(tmp_path / "cache.db")
    pantry = [PantryItem(id=uuid4(), name="Ouă", quantity=4)]
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as transport:
        ai = OllamaProvider("http://local", "test", transport, db)
        first = await ai.generate(RecipeRequest(), pantry)
        second = await ai.generate(RecipeRequest(), pantry)
        assert calls == 1 and second.selection_cached
        saved = db.save_recommendation(first)
        assert db.save_recommendation(second).id == saved.id
        pantry[0] = pantry[0].model_copy(update={"quantity": 3})
        assert (await ai.generate(RecipeRequest(), pantry)).selection_cached
        assert calls == 1
        pantry[0] = pantry[0].model_copy(update={"quantity": 1})
        await ai.generate(RecipeRequest(), pantry)
        assert calls == 2
