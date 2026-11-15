from uuid import uuid4

from fastapi.testclient import TestClient

from app.catalog import candidates, quick_suggestions
from app.main import create_app
from app.models import Ingredient, PantryItem, PersonalRecipe, RecipeRequest
from test_api import HEADERS, KEY


def test_suggestions_share_catalog_filters_and_rank_by_shortage_then_time():
    stock = [PantryItem(id=uuid4(), name=name, quantity=amount, unit=unit) for name, amount, unit in [
        ("Ouă", 6, "buc"), ("Cartofi", 6, "buc"), ("Orez", 300, "g"), ("Morcov", 200, "g"),
        ("Roșii", 6, "buc"), ("Ovăz", 300, "g"), ("Lapte", 1, "l"), ("Ulei", 50, "ml"),
    ]]
    request = RecipeRequest(equipment=["plită", "tigaie", "oală", "cuptor", "microunde"])
    eligible = candidates(request, stock, limit=None)
    assert len(eligible) > 5
    expected = sorted(eligible, key=lambda row: (len(row[1].missing_ingredients), row[1].minutes, row[0].id))
    suggestions = quick_suggestions(request, stock)
    assert [item.title for item in suggestions] == [draft.title for _, draft in expected[:5]]
    assert [item.id for item in suggestions] == [item.id for item in quick_suggestions(request, stock)]


def test_quick_endpoint_never_uses_ollama_and_applies_stock_and_filters(tmp_path):
    # An object without an AI API ensures even an indirect status or generation call fails this test.
    with TestClient(create_app(tmp_path / "quick.db", KEY, object()), headers=HEADERS) as client:
        path = "/v1/recipes/suggestions"
        assert client.post(path, json={}, headers={"Authorization": "Bearer invalid"}).status_code == 401
        assert client.post(path, json={}).json() == []
        client.put(f"/v1/pantry/{uuid4()}", json={
            "name": "Cartofi", "quantity": 20, "expires_on": "2020-01-01",
        }).raise_for_status()
        assert client.post(path, json={}).json() == []
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Cartofi", "quantity": 2}).raise_for_status()
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ulei", "quantity": 50, "unit": "ml"}).raise_for_status()
        before = client.get("/v1/snapshot").raise_for_status().json()
        filters = {"equipment": ["microunde"], "max_minutes": 25, "diet": "vegetarian", "servings": 2}
        options = client.post("/v1/recipes/options", json=filters).raise_for_status().json()["options"]
        suggestions = client.post(path, json=filters).raise_for_status().json()
        assert len(suggestions) == len(options) == 1
        assert suggestions[0]["title"] == options[0]["title"]
        assert suggestions[0]["servings"] == 2
        assert suggestions[0]["model"] == "Catalog Cămara"
        assert client.post(path, json={**filters, "category": "breakfast"}).json() == []
        assert client.post(path, json={**filters, "diet": "vegan"}).json() == []
        assert client.post(path, json={**filters, "max_minutes": 15}).json() == []
        insufficient = {**filters, "servings": 3, "max_minutes": 30, "pantry_only": True}
        assert client.post(path, json=insufficient).json() == []
        assert client.get("/v1/snapshot").raise_for_status().json() == before


def test_ingredient_preferences_filter_before_the_five_recipe_limit():
    stock = [
        PantryItem(id=uuid4(), name="Linte roșie", quantity=300, unit="g"),
        PantryItem(id=uuid4(), name="Tofu", quantity=300, unit="g"),
    ]
    recipes = [
        PersonalRecipe(id=uuid4(), title=f"Prânzul {number}", minutes=10, servings=1, diet="vegan",
                       ingredients=[Ingredient(name="Linte roșie", quantity="50 g"),
                                    Ingredient(name="Tofu" if number < 6 else "Roșii", quantity="50 g" if number < 6 else "1 buc")],
                       steps=["Amestecă ingredientele."], updated_at="2026-11-14")
        for number in range(7)
    ]
    request = RecipeRequest(preferences="Țin post și vreau neapărat cu linte, fără soia.")
    options = candidates(request, stock, recipes)
    assert options and all(part.name != "Tofu" for _, draft in options for part in draft.ingredients)
    assert "Prânzul 6" in [draft.title for _, draft in options]
    assert "Prânzul 6" in [draft.title for draft in quick_suggestions(request, stock, recipes)]


def test_unrecognized_ingredient_clause_requests_confirmation(tmp_path):
    with TestClient(create_app(tmp_path / "confirmation.db", KEY, object()), headers=HEADERS) as client:
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
        for path in ("/v1/recipes/options", "/v1/recipes/suggestions"):
            for text in ("Ceva fără ce am mâncat ieri.", "Vreau ceva fără", "Nu vreau fără cumpărături"):
                response = client.post(path, json={"preferences": text})
                assert response.status_code == 422
                assert "Filtre și echipamente" in response.json()["detail"]
def test_repeated_suggestions_share_cache_until_the_data_or_request_changes(tmp_path, monkeypatch):
    from uuid import uuid4

    from fastapi.testclient import TestClient
    from app.main import create_app
    from test_api import HEADERS, KEY

    calls = []

    def count(request, pantry, personal_recipes, *, today):
        calls.append((request.servings, len(pantry)))
        return quick_suggestions(request, pantry, personal_recipes, today=today)

    monkeypatch.setattr("app.main.quick_suggestions", count)
    with TestClient(create_app(tmp_path / "suggest-cache.db", KEY, object()), headers=HEADERS) as client:
        url = "/v1/recipes/suggestions"
        assert client.post(url, json={}).json() == client.post(url, json={}).json() == []
        assert calls == [(1, 0)]
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
        assert client.post(url, json={}).raise_for_status().json()
        assert client.post(url, json={"servings": 2}).raise_for_status().json()
        assert calls == [(1, 0), (1, 1), (2, 1)]
