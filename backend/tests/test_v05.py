from datetime import datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient

from app.catalog import CATALOG, prepare
from app.database import Database, BatchConflict
from app.main import create_app
from app.models import Ingredient, Recipe, RecipeRequest, Snapshot, PantryItem, MealPlan, PreparedBatch, FoodEvent, Mutation, Change, Purchase
from app.weekly import WeekRequest, PlanPrice, preview, needs
from test_api import FakeAi

TODAY = datetime.now(ZoneInfo('Europe/Bucharest')).date()


def recipe():
    return Recipe(**prepare(CATALOG[1], RecipeRequest(equipment=['plita', 'oala']), []).model_dump(), id=uuid4(), model='test', generated_at='', generation_ms=0)


def request(**kwargs):
    return WeekRequest(id=uuid4(), start=TODAY, budget_cents=100000, use_ai=False, **kwargs)


def prices():
    values = {(name, unit) for entry in CATALOG for name, _, unit in entry.parts if name != 'Apă'}
    return [PlanPrice(name=name, quantity=1000 if unit in {'g', 'ml'} else 1, unit=unit, cents=1000 if unit in {'g', 'ml'} else 100) for name, unit in sorted(values)]


def empty():
    return Snapshot(pantry=[], shopping=[], recipes=[])


def test_unknown_prices_never_become_zero_cost_confirmable_plan():
    result = preview(empty(), request(days=2))
    assert len(result['meals']) == 2
    assert result['total_cents'] is None and result['action'] is None
    assert result['lines'] and any(row['package_cents'] is None for row in result['lines'])


def test_priced_plan_preserves_existing_meals_and_saves_idempotently(tmp_path):
    db = Database(tmp_path / 'priced.db')
    fixed = MealPlan(id=uuid4(), on_date=TODAY, recipe=recipe(), servings=1)
    db.put('plans', fixed)
    result = preview(db.snapshot(), request(days=3, prices=prices()))
    assert len(result['meals']) == 2 and result['existing_meals'] == 1
    assert result['total_cents'] <= 100000
    mutation = result['action']
    assert mutation is not None
    action_id = uuid4()
    db.mutate(action_id, mutation)
    assert db.mutate(action_id, mutation).replayed
    assert len(db.snapshot().plans) == 3 and db.snapshot().shopping
    assert next(row for row in db.snapshot().plans if row.id == fixed.id) == fixed


def test_budget_failure_cannot_produce_a_confirmable_action():
    raw = request(days=7, prices=prices()).model_copy(update={'budget_cents': 1})
    result = preview(empty(), raw)
    assert result['known_cents'] > 1 and result['action'] is None


def test_structured_exclusions_apply_to_every_generated_meal():
    result = preview(empty(), request(days=3, prices=prices(), exclude=['ciuperci', 'ouă']))
    assert result['meals']
    for meal in result['meals']:
        assert not {'ciuperci', 'oua'}.intersection(part.name.casefold().replace('ă', 'a') for part in meal.recipe.ingredients)


def test_extreme_package_prices_are_rejected_before_numeric_overflow():
    with pytest.raises(ValueError):
        PlanPrice(name='Paste', quantity=1e-100, unit='g', cents=100)
    from app.weekly import estimate, price_book
    raw = request(days=1, prices=[PlanPrice(name='Paste', quantity=.0001, unit='g', cents=100_000_000)])
    meal = MealPlan(id=uuid4(), on_date=TODAY, recipe=recipe(), servings=1)
    with pytest.raises(ValueError, match='ambalajului'):
        estimate(empty(), [meal], price_book(empty(), raw))
    from app.models import ReceiptRecord, ReceiptLine
    old = ReceiptRecord(id=uuid4(), image_sha256='a' * 64, merchant='Istoric', spent_on=TODAY, total_cents=100, expense_id=uuid4(), imported_at='',
                        lines=[ReceiptLine(name='Paste', quantity=1e-300, unit='g', line_cents=100)])
    assert price_book(empty().model_copy(update={'receipts': [old]}), request()) == {}


def test_expiry_and_unit_conversions_apply_at_the_planned_meal():
    meal = MealPlan(id=uuid4(), on_date=TODAY + timedelta(days=1), recipe=recipe(), servings=1)
    stock = [PantryItem(id=uuid4(), name='Paste', quantity=.1, unit='kg', expires_on=TODAY),
             PantryItem(id=uuid4(), name='Paste', quantity=.05, unit='kg', expires_on=TODAY + timedelta(days=2))]
    missing = needs(empty().model_copy(update={'pantry': stock}), [meal])
    assert missing[('paste', 'g')][1] == pytest.approx(50)


def test_plan_guard_rejects_stale_inventory_without_partial_meals(tmp_path):
    db = Database(tmp_path / 'guard.db')
    result = preview(db.snapshot(), request(days=2, prices=prices()))
    db.put('pantry', PantryItem(id=uuid4(), name='Ouă', quantity=6))
    with pytest.raises(BatchConflict):
        db.mutate(uuid4(), result['action'])
    assert not db.snapshot().plans and not db.snapshot().shopping


def make_batch(db):
    batch = PreparedBatch(id=uuid4(), recipe=recipe(), cooked_on=TODAY, expires_on=TODAY + timedelta(days=2), total=3, remaining=3)
    event = FoodEvent(id=uuid4(), kind='prepared', title=batch.recipe.title, occurred_on=TODAY, batch_id=batch.id, portions=3)
    db.mutate(uuid4(), Mutation(label='Lot gătit', changes=[Change(kind='prepared', id=batch.id, after=batch.model_dump(mode='json')), Change(kind='events', id=event.id, after=event.model_dump(mode='json'))]))
    return batch, event


def test_prepared_meals_are_reserved_without_buying_ingredients_again(tmp_path):
    db = Database(tmp_path / 'prepared.db')
    batch, _ = make_batch(db)
    result = preview(db.snapshot(), request(days=2, prices=prices()))
    assert all(meal.prepared_batch_id == batch.id for meal in result['meals'])
    assert result['total_cents'] == 0 and result['lines'] == []
    db.mutate(uuid4(), result['action'])
    assert len(db.snapshot().plans) == 2 and not db.snapshot().shopping


def test_prepared_recipe_with_same_title_does_not_bypass_diet_filter():
    matching_title = recipe()
    different_dish = matching_title.model_copy(update={
        'ingredients': [Ingredient(name='Pui', quantity='200 g')],
    })
    batch = PreparedBatch(id=uuid4(), recipe=different_dish, cooked_on=TODAY,
                          expires_on=TODAY + timedelta(days=2), total=3, remaining=3)
    result = preview(empty().model_copy(update={'prepared': [batch]}), request(days=1, prices=prices(), diet='vegan'))
    assert result['meals'] and result['meals'][0].prepared_batch_id is None


def test_cannot_overbook_or_decrement_prepared_portions_without_an_event(tmp_path):
    db = Database(tmp_path / 'overbook.db')
    batch, _ = make_batch(db)
    plans = [MealPlan(id=uuid4(), on_date=TODAY, recipe=batch.recipe, servings=2, prepared_batch_id=batch.id) for _ in range(2)]
    with pytest.raises(BatchConflict):
        db.mutate(uuid4(), Mutation(label='Prea multe porții', changes=[Change(kind='plans', id=row.id, after=row.model_dump(mode='json')) for row in plans]))
    assert not db.snapshot().plans
    with pytest.raises(BatchConflict):
        db.mutate(uuid4(), Mutation(label='Consum fără istoric', changes=[Change(kind='prepared', id=batch.id, before=batch.model_dump(mode='json'), after=batch.model_copy(update={'remaining': 2}).model_dump(mode='json'))]))
    assert db.snapshot().prepared[0].remaining == 3


def test_portion_consumption_and_undo_do_not_touch_raw_ingredients(tmp_path):
    db = Database(tmp_path / 'serve.db')
    batch, _ = make_batch(db)
    event = FoodEvent(id=uuid4(), kind='served', title=batch.recipe.title, occurred_on=TODAY, batch_id=batch.id, portions=1)
    next_batch = batch.model_copy(update={'remaining': 2})
    mutation = Mutation(label='Am mâncat', changes=[Change(kind='prepared', id=batch.id, before=batch.model_dump(mode='json'), after=next_batch.model_dump(mode='json')), Change(kind='events', id=event.id, after=event.model_dump(mode='json'))])
    db.mutate(uuid4(), mutation)
    assert db.snapshot().prepared[0].remaining == 2 and db.snapshot().pantry == []
    undo = Mutation(label='Anulare', changes=[Change(kind='prepared', id=batch.id, before=next_batch.model_dump(mode='json'), after=batch.model_dump(mode='json')), Change(kind='events', id=event.id, before=event.model_dump(mode='json'), after=event.model_copy(update={'undone': True}).model_dump(mode='json'))])
    db.mutate(uuid4(), undo)
    assert db.snapshot().prepared[0].remaining == 3 and db.snapshot().pantry == []


def test_checkout_total_validation_and_authenticated_preview(tmp_path):
    with pytest.raises(ValueError):
        Purchase(id=uuid4(), merchant='Magazin', spent_on=TODAY, total_cents=200, expense_id=uuid4(), lines=[{'name': 'Lapte', 'line_cents': 100}])
    key = 'v05-' + 'x' * 40
    with TestClient(create_app(tmp_path / 'api.db', key, FakeAi())) as client:
        body = request(days=1).model_dump(mode='json')
        assert client.post('/v1/plans/preview', json=body).status_code == 401
        result = client.post('/v1/plans/preview', json=body, headers={'Authorization': 'Bearer ' + key})
        assert result.status_code == 200 and result.json()['action'] is None
