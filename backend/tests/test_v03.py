import sqlite3
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.database import Database
from app.models import PantryItem
from test_api import FakeAi

KEY = 'v03-key-' + 'z' * 40
HEADERS = {'Authorization': 'Bearer ' + KEY}


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path / 'v03.db', KEY, FakeAi()), headers=HEADERS) as value:
        yield value


def snapshot(client):
    return client.get('/v1/snapshot').raise_for_status().json()


def change(kind, before, after):
    return {'kind': kind, 'id': (before or after)['id'], 'before': before, 'after': after}


def action(client, changes, action_id=None):
    return client.put(f'/v1/actions/{action_id or uuid4()}', json={'label': 'Acțiune test', 'changes': changes})


def stock(client, quantity=6):
    item_id = str(uuid4())
    client.put(f'/v1/pantry/{item_id}', json={'name': 'Ouă', 'quantity': quantity}).raise_for_status()
    return next(item for item in snapshot(client)['pantry'] if item['id'] == item_id)


def event(item, quantity=2):
    return {'id': str(uuid4()), 'kind': 'cooked', 'title': 'Omletă', 'occurred_on': '2026-11-01', 'allocations': [{'item': item, 'quantity': quantity}], 'value_cents': None, 'reason': '', 'undone': False, 'plan_id': None}


def test_consumption_is_atomic_and_retry_cannot_subtract_twice(client):
    item = stock(client)
    log = event(item)
    action_id = uuid4()
    changes = [change('pantry', item, {**item, 'quantity': 4}), change('events', None, log)]
    assert action(client, changes, action_id).json() == {'replayed': False}
    assert action(client, changes, action_id).json() == {'replayed': True}
    assert snapshot(client)['pantry'][0]['quantity'] == 4
    assert len(snapshot(client)['events']) == 1
    # A late retry must also preserve edits made after the acknowledged consumption.
    current = snapshot(client)['pantry'][0]
    assert action(client, [change('pantry', current, {**current, 'quantity': 3})]).status_code == 200
    assert action(client, changes, action_id).json()['replayed']
    assert snapshot(client)['pantry'][0]['quantity'] == 3
    assert action(client, [change('pantry', item, {**item, 'quantity': 1})], action_id).status_code == 409


def test_two_devices_cannot_overwrite_stock_and_conflict_rolls_back_whole_action(client):
    item = stock(client)
    assert action(client, [change('pantry', item, {**item, 'quantity': 4})]).status_code == 200
    log = event(item, 3)
    response = action(client, [change('events', None, log), change('pantry', item, {**item, 'quantity': 3})])
    assert response.status_code == 409
    assert snapshot(client)['events'] == []
    assert snapshot(client)['pantry'][0]['quantity'] == 4


def test_undo_restores_deleted_batch_once_and_records_cancellation(client):
    item = stock(client, 2)
    log = event(item)
    assert action(client, [change('pantry', item, None), change('events', None, log)]).status_code == 200
    undo_id = uuid4()
    undo = [change('pantry', None, item), change('events', log, {**log, 'undone': True})]
    assert action(client, undo, undo_id).status_code == 200
    assert action(client, undo, undo_id).json()['replayed']
    assert snapshot(client)['pantry'][0]['quantity'] == 2
    assert snapshot(client)['events'][0]['undone']
    assert action(client, undo).status_code == 409


def test_plans_finances_and_waste_persist_in_common_snapshot(client):
    recipe = client.post('/v1/recipes/generate', json={}).json()
    plan = {'id': str(uuid4()), 'on_date': '2026-11-03', 'slot': 'lunch', 'recipe': recipe, 'servings': 2, 'completed': False}
    expense = {'id': str(uuid4()), 'label': 'Cumpărături', 'amount_cents': 2599, 'spent_on': '2026-11-01', 'category': 'Alimente'}
    budget = {'id': str(uuid4()), 'month': '2026-11', 'limit_cents': 60000}
    assert action(client, [change('plans', None, plan), change('expenses', None, expense), change('budgets', None, budget)]).status_code == 200
    value = snapshot(client)
    assert value['plans'][0]['servings'] == 2
    assert value['expenses'][0]['amount_cents'] == 2599
    assert value['budgets'][0]['month'] == '2026-11'
    assert value['aliases']['ou'] == 'oua'
    item = stock(client)
    log = {**event(item), 'kind': 'wasted', 'value_cents': 350, 'reason': 'Alterat'}
    assert action(client, [change('pantry', item, {**item, 'quantity': 4}), change('events', None, log), change('plans', plan, {**plan, 'completed': True})]).status_code == 200
    assert snapshot(client)['plans'][0]['completed']
    assert snapshot(client)['events'][0]['value_cents'] == 350


@pytest.mark.parametrize('kind,data', [
    ('expenses', {'label': 'Test', 'amount_cents': -1, 'spent_on': '2026-11-01'}),
    ('expenses', {'label': 'Test', 'amount_cents': 1.234, 'spent_on': '2026-11-01'}),
    ('budgets', {'month': '2026-13', 'limit_cents': 0}),
    ('pantry', {'name': 'Test', 'quantity': 0}),
])
def test_invalid_money_dates_or_quantities_cannot_partially_save(client, kind, data):
    item = stock(client)
    invalid = {'id': str(uuid4()), **data}
    assert action(client, [change('pantry', item, None), change(kind, None, invalid)]).status_code == 422
    assert snapshot(client)['pantry'] == [item]


def test_action_identity_validation_and_authentication(client):
    item = stock(client)
    same = change('pantry', item, {**item, 'quantity': 3})
    assert action(client, [same, same]).status_code == 422
    assert action(client, [{**same, 'id': str(uuid4())}]).status_code == 422
    response = client.put(f'/v1/actions/{uuid4()}', json={'label': 'Test', 'changes': [same]}, headers={'Authorization': 'Bearer invalid'})
    assert response.status_code == 401
    assert snapshot(client)['pantry'] == [item]


def test_schema_migration_preserves_previous_data_and_restarts(tmp_path):
    path = tmp_path / 'old.db'
    item = PantryItem(id=uuid4(), name='Lapte', quantity=1, unit='l')
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE pantry (id TEXT PRIMARY KEY, data TEXT NOT NULL)')
        db.execute('INSERT INTO pantry VALUES (?,?)', (str(item.id), item.model_dump_json()))
        db.execute('PRAGMA user_version=2')
    migrated = Database(path)
    assert migrated.snapshot().pantry == [item]
    assert Database(path).snapshot().pantry == [item]
    with sqlite3.connect(path) as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 6


def test_shopping_calculation_detects_new_items_from_another_device(client):
    item = stock(client)
    before = snapshot(client)
    stock(client, 3)
    shopping = {'id': str(uuid4()), 'name': 'Ouă', 'quantity': '2 buc', 'checked': False}
    response = client.put(f'/v1/actions/{uuid4()}', json={
        'label': 'Cumpărături pentru plan', 'changes': [change('shopping', None, shopping)],
        'guards': {'pantry': before['pantry'], 'plans': [], 'shopping': []}})
    assert response.status_code == 409
    assert snapshot(client)['shopping'] == []
