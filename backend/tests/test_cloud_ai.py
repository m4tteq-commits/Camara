import json
from datetime import timedelta
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.catalog import recipe_day
from app.database import Database
from app.main import create_app
from app.models import Recipe
from test_api import HEADERS, KEY


def test_recommendation_deduplicates_current_selection_and_preserves_changed_selection(tmp_path):
    db = Database(tmp_path / "choices.db")
    recipe = Recipe(id=uuid4(), title="Supă", minutes=15, servings=1, model="test",
                    ingredients=[{"name": "Roșii", "quantity": "2 buc"}],
                    steps=["Fierbe roșiile."], generated_at="", generation_ms=1, request_key="same_inputs")
    first = db.save_recommendation(recipe)
    identical = recipe.model_copy(update={"id": uuid4(), "generation_ms": 0, "selection_cached": True})
    assert db.save_recommendation(identical).id == first.id
    changed = identical.model_copy(update={"title": "Sos de roșii", "selection_cached": False})
    assert db.save_recommendation(changed).id == changed.id
    assert db.save_recommendation(identical.model_copy(update={"id": uuid4(), "title": changed.title})).id == changed.id
    assert [item.title for item in db.snapshot().recipes] == ["Sos de roșii", "Supă"]


def test_recipe_endpoints_do_not_load_unrelated_tables(tmp_path, monkeypatch):
    def full_snapshot(_):
        raise AssertionError("The recipe endpoint requested an unnecessary full snapshot")

    monkeypatch.setattr(Database, "snapshot", full_snapshot)
    with TestClient(create_app(tmp_path / "recipes.db", KEY, object()), headers=HEADERS) as client:
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
        assert client.post("/v1/recipes/suggestions", json={}).raise_for_status().json()
        assert client.post("/v1/recipes/options", json={}).raise_for_status().json()["options"]


def test_cloud_providers_are_selectable_and_cache_independently(tmp_path, monkeypatch):
    model = "openai/gpt-oss-120b"
    for prefix in ("OPENROUTER", "GROQ"):
        monkeypatch.setenv(f"{prefix}_API_KEY", f"test-key-{prefix}")
        monkeypatch.setenv(f"{prefix}_MODEL", model)

    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": '{"recipe_id":"1"}'}},
        ]})

    app = create_app(tmp_path / "providers.db", KEY, object())
    with TestClient(app, headers=HEADERS) as client:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            for provider in app.state.cloud_ai.values():
                provider.client = remote
            assert client.get("/v1/ai/providers", headers={"Authorization": ""}).status_code == 401
            assert client.get("/v1/ai/providers").json() == {"openrouter": model, "groq": model}
            for name in ("Ouă", "Marcaj inventar privat"):
                client.put(f"/v1/pantry/{uuid4()}", json={"name": name, "quantity": 6,
                           "expires_on": (recipe_day() + timedelta(days=1)).isoformat()}).raise_for_status()
            data = {"preferences": "Mai rapid"}
            router = client.post("/v1/recipes/cloud/openrouter", json=data).raise_for_status().json()
            groq = client.post("/v1/recipes/cloud/groq", json=data).raise_for_status().json()
            repeat = client.post("/v1/recipes/cloud/groq", json=data).raise_for_status().json()
            assert router["model"] == f"OpenRouter · {model}"
            assert groq["model"] == f"GroqCloud · {model}"
            assert router["id"] != groq["id"]
            assert repeat["id"] == groq["id"] and repeat["selection_cached"]
            assert router["ingredients"] and router["steps"]
            assert [item["name"] for item in groq["expiring_ingredients"]] == ["Ouă"]
            assert len(client.get("/v1/snapshot").json()["recipes"]) == 2
            assert client.post("/v1/recipes/cloud/nous", json=data).status_code == 422
        finally:
            client.portal.call(remote.aclose)

    assert [str(request.url) for request in calls] == [
        "https://openrouter.ai/api/v1/chat/completions",
        "https://api.groq.com/openai/v1/chat/completions",
    ]
    assert [request.headers["Authorization"] for request in calls] == [
        "Bearer test-key-OPENROUTER", "Bearer test-key-GROQ",
    ]
    for request in calls:
        payload = json.loads(request.content)
        data = json.loads(payload["messages"][1]["content"])
        assert data["preferences"] == "Mai rapid"
        assert data["options"] and all(set(item) == {"id", "title", "minutes", "ingredients", "missing_ingredients", "expires_soon"} for item in data["options"])
        assert "Marcaj inventar privat" not in json.dumps(data)
        assert any({"name": "Ouă", "in_days": 1} in row["expires_soon"] for row in data["options"])
    assert json.loads(calls[0].content)["response_format"]["type"] == "json_schema"
    assert json.loads(calls[0].content)["max_tokens"] == 768
    groq_payload = json.loads(calls[1].content)
    assert groq_payload["response_format"]["json_schema"]["strict"] is True
    assert groq_payload["max_completion_tokens"] == 768 and groq_payload["reasoning_effort"] == "low"
    assert "provider" not in groq_payload


def test_cloud_errors_without_backup_are_reported_without_saving(tmp_path, monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-router")
    monkeypatch.delenv("OPENROUTER_MODEL", raising=False)
    monkeypatch.setenv("GROQ_API_KEY", "test-groq")
    monkeypatch.setenv("GROQ_MODEL", "test/recipe-model")
    replies = iter([
        httpx.Response(402, text="provider error includes private values"),
        httpx.Response(429),
        httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": '{"recipe_id":"unlisted"}'}},
        ]}),
    ])
    app = create_app(tmp_path / "failed.db", KEY, object())
    with TestClient(app, headers=HEADERS) as client:
        assert client.get("/v1/ai/providers").json() == {"groq": "test/recipe-model"}
        disabled = client.post("/v1/recipes/cloud/openrouter", json={})
        assert disabled.status_code == 503 and "ZimaOS" in disabled.json()["detail"]
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
        remote = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: next(replies)))
        try:
            app.state.cloud_ai["groq"].client = remote
            for expected, detail in ((503, "soldul"), (429, "limita"), (502, "alegere invalidă")):
                result = client.post("/v1/recipes/cloud/groq", json={})
                assert result.status_code == expected
                assert detail in result.json()["detail"]
                assert "private values" not in result.text
            assert client.get("/v1/snapshot").json()["recipes"] == []
            assert client.post("/v1/recipes/suggestions", json={}).raise_for_status().json()
        finally:
            client.portal.call(remote.aclose)


@pytest.mark.parametrize("failure", [429, 503, "timeout", "unconfigured"])
def test_groq_falls_back_to_openrouter_then_recovers(tmp_path, monkeypatch, failure):
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-router")
    monkeypatch.setenv("OPENROUTER_MODEL", "test/backup")
    monkeypatch.setenv("GROQ_API_KEY", "" if failure == "unconfigured" else "test-groq")
    monkeypatch.setenv("GROQ_MODEL", "openai/gpt-oss-120b")
    calls = []

    def respond(request):
        calls.append(request.url.host)
        if calls == ["api.groq.com"]:
            if failure == "timeout":
                raise httpx.ReadTimeout("timeout", request=request)
            if isinstance(failure, int):
                return httpx.Response(failure)
        return httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": '{"recipe_id":"1"}'}},
        ]})

    app = create_app(tmp_path / "failover.db", KEY, object())
    with TestClient(app, headers=HEADERS) as client:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            for provider in app.state.cloud_ai.values():
                provider.client = remote
            client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
            path = "/v1/recipes/cloud/groq"
            first = client.post(path, json={}).raise_for_status().json()
            assert first["model"] == "OpenRouter · test/backup"
            assert calls == (["openrouter.ai"] if failure == "unconfigured" else ["api.groq.com", "openrouter.ai"])
            assert len(client.get("/v1/snapshot").json()["recipes"]) == 1
            app.state.cloud_ai["groq"].key = "test-groq"
            second = client.post(path, json={}).raise_for_status().json()
            assert second["model"] == "GroqCloud · openai/gpt-oss-120b"
            assert calls[-1] == "api.groq.com"
        finally:
            client.portal.call(remote.aclose)


def test_groq_valid_no_match_does_not_use_backup(tmp_path, monkeypatch):
    for prefix in ("OPENROUTER", "GROQ"):
        monkeypatch.setenv(f"{prefix}_API_KEY", f"test-key-{prefix}")
        monkeypatch.setenv(f"{prefix}_MODEL", "test/model")
    calls = []

    def respond(request):
        calls.append(request.url.host)
        return httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": '{"recipe_id":"none"}'}},
        ]})

    app = create_app(tmp_path / "no-match.db", KEY, object())
    with TestClient(app, headers=HEADERS) as client:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            for provider in app.state.cloud_ai.values():
                provider.client = remote
            client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
            result = client.post("/v1/recipes/cloud/groq", json={"preferences": "Vreau ceva cu ananas"})
            assert result.status_code == 422 and "Nicio rețetă" in result.json()["detail"]
            assert calls == ["api.groq.com"]
            assert client.get("/v1/snapshot").json()["recipes"] == []
        finally:
            client.portal.call(remote.aclose)
