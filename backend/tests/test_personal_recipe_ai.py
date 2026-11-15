import json
from uuid import uuid4

import httpx
from fastapi.testclient import TestClient

from app.catalog import candidates, recipe_day
from app.database import Database
from app.main import create_app
from app.models import MealPlan, PantryItem, PersonalRecipe, RecipeRequest, Snapshot
from app.personal_recipe_ai import advice_input
from app.weekly import WeekRequest, preview
from test_api import HEADERS, KEY


def test_personal_recipe_ai_previews_are_reviewable_and_read_only(tmp_path, monkeypatch):
    for prefix in ("OPENROUTER", "GROQ"):
        monkeypatch.setenv(f"{prefix}_API_KEY", "test")
        monkeypatch.setenv(f"{prefix}_MODEL", "openai/gpt-oss-120b")
    path = tmp_path / "personal.db"
    recipe = PersonalRecipe(id=uuid4(), title="Orez cu morcov", minutes=20, servings=2,
                            ingredients=[{"name": "Orez", "quantity": "200 G"}, {"name": "Morcov", "quantity": "2buc"}],
                            steps=["Taie morcovul.", "Fierbe orezul."], updated_at="2026-11-14")
    Database(path).put("personal_recipes", recipe)
    calls = []

    def respond(request):
        payload = json.loads(request.content)
        schema = payload["response_format"]["json_schema"]
        data = json.loads(payload["messages"][1]["content"])
        calls.append((request.url.host, schema["name"]))
        if schema["name"] == "personal_recipe_import":
            assert schema["schema"]["properties"]["title"]["type"] == "string"
            assert data == {"text": "Fierbe orezul cu un praf de sare."}
            if request.url.host == "api.groq.com":
                return httpx.Response(429)
            result = {"title": "", "minutes": 15, "minutes_estimated": True, "servings": None,
                      "ingredients": [{"name": "Sare", "amount": "", "unit": "buc", "original": "un praf de sare",
                                       "quantity_text": "un praf"}],
                      "steps": ["Fierbe orezul."], "notes": []}
        else:
            assert data["missing"] == ["Morcov"]
            assert data["pantry"] == ["Orez"]
            step = 3 if data["goal"] == "pantry" and request.url.host == "api.groq.com" else 1
            result = {"tips": [{"step": step, "suggestion": "Pregătește morcovul înainte de a porni plita."}]}
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})

    app = create_app(path, KEY, object())
    with TestClient(app, headers=HEADERS) as api:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            for provider in app.state.cloud_ai.values():
                provider.client = remote
            api.put(f"/v1/pantry/{uuid4()}", json={"name": "Orez", "quantity": 0.2, "unit": "kg"}).raise_for_status()
            snapshot = api.get("/v1/snapshot").raise_for_status().json()
            draft = api.post("/v1/personal-recipes/import-preview", json={"text": "Fierbe orezul cu un praf de sare."}).raise_for_status().json()
            assert draft["minutes"] == 15 and draft["minutes_estimated"] and draft["ingredients"][0]["quantity_text"] == "un praf"
            result = api.post(f"/v1/personal-recipes/{recipe.id}/advice", json={"goal": "pantry"}).raise_for_status().json()
            assert result["tips"][0]["step"] == 1 and result["missing"] == ["Morcov"]
            assert api.post(f"/v1/personal-recipes/{uuid4()}/advice", json={"goal": "pantry"}).status_code == 404
            assert api.get("/v1/snapshot").raise_for_status().json() == snapshot
        finally:
            api.portal.call(remote.aclose)
    assert calls == [("api.groq.com", "personal_recipe_import"), ("openrouter.ai", "personal_recipe_import"),
                     ("api.groq.com", "personal_recipe_advice"), ("openrouter.ai", "personal_recipe_advice")]


def test_unmeasured_ingredients_survive_suggestions_and_keep_the_budget_explicit(tmp_path):
    recipe = PersonalRecipe(id=uuid4(), title="Orezul meu", minutes=20, minutes_estimated=True, servings=2,
                            ingredients=[{"name": "Sare", "quantity": "un vârf de cuțit"},
                                         {"name": "Orez", "quantity": "200 g"},
                                         {"name": "Piper", "quantity": "Nespecificată"}],
                            steps=["Fierbe orezul."], updated_at="2026-11-15")
    db = Database(tmp_path / "unmeasured.db")
    db.put("personal_recipes", recipe)
    stock = [PantryItem(id=uuid4(), name="Orez", quantity=300, unit="g")]
    snapshot = Snapshot(pantry=stock, shopping=[], recipes=[], personal_recipes=[recipe])
    saved = db.snapshot().personal_recipes[0]
    assert saved == recipe and saved.minutes_estimated
    own = next(draft for entry, draft in candidates(RecipeRequest(servings=1), stock, [saved], limit=None)
               if entry.id == f"personal-{saved.id}")
    assert [part.quantity for part in own.ingredients] == ["un vârf de cuțit", "100 g", "Nespecificată"]
    assert advice_input(saved, "pantry", snapshot)["missing"] == ["Sare", "Piper"]
    meal = MealPlan(id=uuid4(), on_date=recipe_day(), slot="dinner", recipe={
        **own.model_dump(), "id": str(uuid4()), "model": "test", "generated_at": "", "generation_ms": 0,
    }, servings=1)
    plan = preview(snapshot.model_copy(update={"plans": [meal]}),
                   WeekRequest(id=uuid4(), start=recipe_day(), days=1, slots=["dinner"], budget_cents=10000, use_ai=False))
    assert plan["total_cents"] is None and plan["action"] is None
    assert any("Sare" in warning and "Piper" in warning for warning in plan["warnings"])
