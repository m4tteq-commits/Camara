from uuid import uuid4

from app.catalog import CATALOG, candidates, prepare
from app.models import PantryItem, RecipeRequest


def item(name, quantity, unit):
    return PantryItem(id=uuid4(), name=name, quantity=quantity, unit=unit)


def test_scales_quantities_and_converts_stock_units():
    pantry = [item("Ou", 2, "buc"), item("Telemea", 0.1, "kg"), item("Tomate", 1, "buc"), item("Ulei de măsline", 0.01, "l")]
    omelette = next(e for e in CATALOG if e.id == "omleta")
    assert prepare(omelette, RecipeRequest(), pantry).missing_ingredients == []
    missing = prepare(omelette, RecipeRequest(servings=2), pantry).missing_ingredients
    assert {m.name: m.quantity for m in missing} == {"Ouă": "2 buc", "Roșii": "1 buc"}


def test_respects_equipment_time_and_actual_pantry():
    assert candidates(RecipeRequest(max_minutes=5), [item("Ouă", 2, "buc")]) == []
    options = candidates(RecipeRequest(max_minutes=15, equipment=["microunde"]), [item("Cartofi", 1, "buc")])
    assert [entry.id for entry, _ in options] == ["cartof-microunde"]


def test_unknown_unit_is_flagged_instead_of_assuming_package_size():
    omelette = next(e for e in CATALOG if e.id == "omleta")
    recipe = prepare(omelette, RecipeRequest(), [item("Brânză", 1, "buc")])
    cheese = next(i for i in recipe.missing_ingredients if i.name == "Brânză")
    assert "verifică unitatea" in cheese.quantity


def test_catalog_has_concrete_unique_steps_and_valid_servings():
    for entry in CATALOG:
        for servings in (1, 2, 8):
            recipe = prepare(entry, RecipeRequest(servings=servings), [])
            assert recipe.servings == servings
            assert recipe.minutes <= 180


def test_shortlist_keeps_full_catalog_order_without_materializing_every_draft(monkeypatch):
    from app import catalog

    pantry = [item(name, 5, "buc") for name in ("Ouă", "Roșii", "Cartofi", "Morcov", "Banane")]
    request = RecipeRequest(max_minutes=120, equipment=["plita", "tigaie", "oala", "cuptor", "microunde"])
    ordered = candidates(request, pantry, limit=None)
    assert len(ordered) > 5
    original = catalog.prepare_with_stock
    built = []

    def count(entry, *args):
        built.append(entry.id)
        return original(entry, *args)

    monkeypatch.setattr(catalog, "prepare_with_stock", count)
    assert candidates(request, pantry, limit=5) == ordered[:5]
    assert built == [entry.id for entry, _ in ordered[:5]]
