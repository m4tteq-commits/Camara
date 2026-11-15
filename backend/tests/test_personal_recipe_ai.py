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
            step = 1
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
                     ("api.groq.com", "personal_recipe_advice")]


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


def test_conversation_reuses_variant_and_notes_without_saving(tmp_path, monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "test")
    monkeypatch.setenv("GROQ_MODEL", "openai/gpt-oss-120b")
    path = tmp_path / "conversation.db"
    recipe = PersonalRecipe(id=uuid4(), title="Terci", minutes=10, servings=1,
                            ingredients=[{"name": "Ovăz", "quantity": "50 g"}], steps=["Fierbe ovăzul."],
                            updated_at="2026-11-14", favorite=True, collections=["Mic dejun"],
                            notes="Mai puțină sare", actual_minutes=12)
    Database(path).put("personal_recipes", recipe)
    variant = {"title": "Terci pentru doi", "minutes": 10, "minutes_estimated": True, "servings": 2,
               "ingredients": [{"name": "Ovăz", "amount": "100", "unit": "g", "original": "100 g ovăz", "quantity_text": ""}],
               "steps": ["Fierbe ovăzul."], "notes": []}
    inputs = []

    def respond(request):
        payload = json.loads(request.content)
        inputs.append(json.loads(payload["messages"][1]["content"]))
        assert payload["response_format"]["json_schema"]["name"] == "recipe_conversation"
        result = {"answer": "Folosește 100 g de ovăz.", "variant": variant if len(inputs) == 1 else None}
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})

    app = create_app(path, KEY, object())
    with TestClient(app, headers=HEADERS) as api:
        remote = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        try:
            app.state.cloud_ai["groq"].client = remote
            api.put(f"/v1/pantry/{uuid4()}", json={"name": "Ovăz", "quantity": 200, "unit": "g"}).raise_for_status()
            before = api.get("/v1/snapshot").json()
            assert before["personal_recipes"][0]["collections"] == ["Mic dejun"]
            assert before["personal_recipes"][0]["favorite"] is True
            endpoint = f"/v1/personal-recipes/{recipe.id}/conversation"
            question = "Adaptează pentru două porții."
            first = api.post(endpoint, json={"question": question}).raise_for_status().json()
            next_turn = {"question": "Pot pregăti în avans?",
                         "history": [{"question": question, "answer": first["answer"]}], "current_variant": first["variant"]}
            assert api.post(endpoint, json=next_turn).raise_for_status().json()["variant"] is None
            assert inputs[0]["notes"] == "Mai puțină sare" and inputs[0]["pantry"] == ["Ovăz"]
            assert inputs[0]["actual_minutes"] == 12
            assert inputs[1]["history"] == next_turn["history"] and inputs[1]["current_variant"] == variant
            assert api.get("/v1/snapshot").json() == before
        finally:
            api.portal.call(remote.aclose)


def test_photo_returns_ocr_text_without_using_ai(tmp_path, monkeypatch):
    def recognize(data):
        assert data == b"photo"
        return "Terci\nIngrediente:\nOvăz\nPași:\nFierbe ovăzul.", 90

    monkeypatch.setattr("app.main.recognize_image_text", recognize)
    with TestClient(create_app(tmp_path / "photo.db", KEY, object()), headers=HEADERS) as api:
        result = api.post("/v1/personal-recipes/photo-text", content=b"photo").raise_for_status().json()
        assert result["text"].startswith("Terci\nIngrediente:")
        assert api.get("/v1/snapshot").json()["personal_recipes"] == []
