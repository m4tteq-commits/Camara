from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor
from threading import Event
import asyncio
import sqlite3
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx
import pytest
from fastapi.testclient import TestClient

from app.ai import AiError, OllamaProvider, selection_context
from app.main import create_app
from app.models import AiStatus, PantryItem, Recipe, RecipeDraft, RecipeRequest

KEY = "test-key-" + "a" * 40
HEADERS = {"Authorization": f"Bearer {KEY}"}


class FakeAi:
    async def status(self):
        return AiStatus(available=True, model="test-model", detail="Pregătit")

    async def generate(self, request, pantry):
        return Recipe(
            id=uuid4(), title="Ouă cu roșii", minutes=15, servings=request.servings,
            ingredients=[{"name": "ouă", "quantity": "2 buc"}, {"name": "roșii", "quantity": "1 buc"}],
            missing_ingredients=[{"name": "roșii", "quantity": "1 buc"}], steps=["Prepară ingredientele în tigaie."],
            model="test-model", generated_at=datetime.now(timezone.utc).isoformat(), generation_ms=1,
        )


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path / "test.db", KEY, FakeAi()), headers=HEADERS) as result:
        yield result


def test_requires_auth_on_all_routes(tmp_path):
    with TestClient(create_app(tmp_path / "auth.db", KEY, FakeAi())) as c:
        for path in ("/health", "/v1/status", "/v1/snapshot", "/v1/ai/status"):
            assert c.get(path).status_code == 401
        assert c.get("/v1/snapshot", headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert c.get("/docs").status_code == 404


def test_configuration_rejects_empty_key(tmp_path):
    with pytest.raises(RuntimeError):
        create_app(tmp_path / "key.db", "")


def test_server_status_reports_database_and_latest_backup_without_ai(tmp_path):
    path = tmp_path / "status.db"
    with TestClient(create_app(path, KEY, object()), headers=HEADERS) as c:
        c.put("/v1/backups/settings", json={"enabled": False}).raise_for_status()
        assert c.get("/health").raise_for_status().json() == {"status": "ok", "version": "0.9.7"}
        before = c.get("/v1/status").raise_for_status().json()
        assert before["database"] == "ready" and before["schema_version"] == 6
        backup = c.post("/v1/backups").raise_for_status().json()
        assert c.get("/v1/status").raise_for_status().json()["last_backup_at"] == backup["created_at"]
        with sqlite3.connect(path) as connection:
            connection.execute("PRAGMA user_version=5")
        for route in ("/health", "/v1/status"):
            assert c.get(route).status_code == 503


def test_pantry_shared_between_clients_and_persists(tmp_path):
    db = tmp_path / "shared.db"
    item_id = str(uuid4())
    item = {"name": " Ouă ", "quantity": 6, "unit": "buc", "expires_on": "2026-11-05"}
    with TestClient(create_app(db, KEY, FakeAi()), headers=HEADERS) as phone:
        assert phone.put(f"/v1/pantry/{item_id}", json=item).status_code == 200
        assert phone.put(f"/v1/pantry/{item_id}", json={**item, "quantity": 4}).status_code == 200
    with TestClient(create_app(db, KEY, FakeAi()), headers=HEADERS) as tablet:
        pantry = tablet.get("/v1/snapshot").json()["pantry"]
        assert len(pantry) == 1
        assert pantry[0]["name"] == "Ouă"
        assert pantry[0]["quantity"] == 4
        assert tablet.delete(f"/v1/pantry/{item_id}").status_code == 204
        assert tablet.get("/v1/snapshot").json()["pantry"] == []


@pytest.mark.parametrize("patch", [{"name": " "}, {"quantity": -1}, {"unit": "invalid"}, {"expires_on": "tomorrow"}])
def test_input_validation(client, patch):
    result = client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 2, **patch})
    assert result.status_code == 422
    assert client.get("/v1/snapshot").json()["pantry"] == []


def test_recipe_to_shopping_is_idempotent(client):
    recipe = client.post("/v1/recipes/generate", json={}).json()
    path = f"/v1/recipes/{recipe['id']}/shopping"
    assert len(client.post(path).json()) == 1
    assert client.post(path).json() == []
    snapshot = client.get("/v1/snapshot").json()
    assert len(snapshot["recipes"]) == 1
    item = snapshot["shopping"][0]
    assert item["name"] == "roșii"
    update = {k: v for k, v in item.items() if k != "id"}
    update["checked"] = True
    assert client.put(f"/v1/shopping/{item['id']}", json=update).json()["checked"]
    assert client.delete(f"/v1/shopping/{item['id']}").status_code == 204


def test_missing_recipe(client):
    assert client.post(f"/v1/recipes/{uuid4()}/shopping").status_code == 404


def test_recommendation_in_flight_cannot_write_after_restore(tmp_path):
    started, finish = Event(), Event()

    class PausedAi(FakeAi):
        async def generate(self, request, pantry):
            started.set()
            assert await asyncio.to_thread(finish.wait, 10)
            return await super().generate(request, pantry)

    with TestClient(create_app(tmp_path / 'race.db', KEY, PausedAi()), headers=HEADERS) as c:
        backup = c.post('/v1/backups').raise_for_status().json()
        path = f"/v1/backups/{backup['id']}"
        preview = c.post(path + '/preview').raise_for_status().json()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(c.post, '/v1/recipes/generate', json={})
            try:
                assert started.wait(5)
                c.post(path + '/restore', json={'request_id': str(uuid4()), 'expected_fingerprint': preview['fingerprint'],
                       'backup_sha256': backup['sha256'], 'confirmation': 'RESTAUREAZĂ'}).raise_for_status()
            finally:
                finish.set()
            assert pending.result(timeout=5).status_code == 409
        assert c.get('/v1/snapshot').raise_for_status().json()['recipes'] == []


@pytest.mark.anyio
@pytest.mark.parametrize("body", [[], {"done": True, "response": '{"recipe_id":"none"}'}])
async def test_invalid_ollama_response_is_a_handled_error(body):
    def respond(request):
        return httpx.Response(200, json=body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as transport:
        ai = OllamaProvider("http://local", "test", transport)
        with pytest.raises(AiError) as caught:
            await ai.generate(RecipeRequest(), [PantryItem(id=uuid4(), name='Ouă', quantity=6)])
        assert caught.value.status_code == 502


@pytest.mark.anyio
async def test_ollama_uses_schema_and_excludes_expired_items():
    def respond(request):
        import json
        body = json.loads(request.content)
        assert body["stream"] is False
        assert body["format"]["type"] == "object"
        assert body["model"] == "local-test"
        assert body["prompt"] == expected_prompt
        return httpx.Response(200, json={"done": True, "response": '{"recipe_id":"omleta"}'})

    today = datetime.now(ZoneInfo("Europe/Bucharest")).date()
    pantry = [
        PantryItem(id=uuid4(), name="Ouă", quantity=2, expires_on=today + timedelta(days=1)),
        PantryItem(id=uuid4(), name="Lapte", quantity=1, expires_on=today - timedelta(days=1)),
    ]
    expected, _ = selection_context(RecipeRequest(), pantry)
    without_expired, _ = selection_context(RecipeRequest(), pantry[:1])
    assert expected.fingerprint == without_expired.fingerprint
    expected_prompt = expected.prompt
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as c:
        recipe = await OllamaProvider("http://local", "local-test", c).generate(RecipeRequest(), pantry)
    assert recipe.title == "Omletă cu brânză și roșii"
    assert recipe.model == "local-test"


FakeAiRecipe = '{"title":"Omletă","minutes":10,"servings":1,"ingredients":[{"name":"ouă","quantity":"2 buc"}],"steps":["Gătește ouăle în tigaie."],"missing_ingredients":[]}'


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.mark.anyio
async def test_invalid_model_response_is_not_persistable():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"done": True, "response": "not json"}))) as c:
        with pytest.raises(AiError) as exc:
            await OllamaProvider("http://local", "bad", c).generate(
                RecipeRequest(), [PantryItem(id=uuid4(), name="Ouă", quantity=2)]
            )
        assert exc.value.status_code == 502


@pytest.mark.anyio
async def test_model_unavailable_status():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"models": []}))) as c:
        status = await OllamaProvider("http://local", "not-installed", c).status()
        assert not status.available


@pytest.mark.parametrize("change", [
    {"ingredients": [{"name": "ouă", "quantity": ""}]},
    {"steps": ["Gătește ouăle.", "Gătește ouăle."]},
])
def test_rejects_incomplete_recipe_content(change):
    import json
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        RecipeDraft.model_validate({**json.loads(FakeAiRecipe), **change})
