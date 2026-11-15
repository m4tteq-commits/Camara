from datetime import datetime, timedelta
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo
import json
import sqlite3

import pytest
from fastapi.testclient import TestClient

from app.ai import selection_context
from app.backups import BackupManager, BackupSettings, RestoreRequest
from app.database import Database
from app.main import create_app
from app.models import PantryItem, Product, PersonalRecipe, RecipeRequest
from app.receipts import parse_receipt, recognize_receipt
from test_api import FakeAi

KEY = 'v04-test-' + 'x' * 40
HEADERS = {'Authorization': 'Bearer ' + KEY}


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path / 'v04.db', KEY, FakeAi()), headers=HEADERS) as value:
        yield value


def snap(client):
    return client.get('/v1/snapshot').raise_for_status().json()


def action(client, changes, epoch='', action_id=None):
    return client.put(f'/v1/actions/{action_id or uuid4()}', json={'label': 'Test v04', 'changes': changes, 'epoch': epoch})


def change(kind, before, after):
    return {'kind': kind, 'id': (before or after)['id'], 'before': before, 'after': after}


def product(**values):
    return Product(id=uuid4(), name='Orez', barcode='5901234123457', package_quantity=500, unit='g', receipt_names=['OREZ TEST'], **values)


def personal():
    return PersonalRecipe(id=uuid4(), title='Quinoa mea', minutes=12, servings=2,
        ingredients=[{'name': 'Quinoa', 'quantity': '200 g'}, {'name': 'Apă', 'quantity': '400 ml'}],
        steps=['Clătește quinoa.', 'Fierbe quinoa până este gata.'], equipment=['Plită', 'Oală'], diet='vegan', category='main', updated_at='2026-11-01T12:00:00Z')


def test_catalog_barcode_uniqueness_rolls_back_whole_action(client):
    first = product().model_dump(mode='json')
    assert action(client, [change('products', None, first)]).status_code == 200
    second = {**first, 'id': str(uuid4()), 'name': 'Alt orez'}
    item = {'id': str(uuid4()), 'name': 'Test', 'quantity': 1, 'unit': 'buc'}
    assert action(client, [change('pantry', None, item), change('products', None, second)]).status_code == 409
    assert snap(client)['pantry'] == []
    assert len(snap(client)['products']) == 1


def test_receipt_preview_matches_packages_and_excludes_deposit():
    text = 'MAGAZIN TEST\n01.11.2026\n2 x OREZ TEST 9,00 A\nGARANTIE SGR 0,50 A\nTOTAL 9,50\nCARD 9,50'
    preview = parse_receipt(text, 'a' * 64, [product()])
    assert preview.total_cents == 950
    assert str(preview.spent_on) == '2026-11-01'
    assert len(preview.lines) == 2
    assert preview.lines[0].name == 'Orez'
    assert preview.lines[0].quantity == 1000 and preview.lines[0].unit == 'g'
    assert preview.lines[1].include_pantry is False


def test_missing_totals_and_unknown_packages_require_review():
    preview = parse_receipt('MAGAZIN\nPRODUS NECUNOSCUT 4.99 A\nTVA 0.45', 'b' * 64, confidence=40)
    assert preview.total_cents is None and preview.spent_on is None
    assert preview.lines[0].warnings
    assert len(preview.warnings) >= 4


def test_weighted_receipt_and_invalid_date():
    result = parse_receipt('MAGAZIN\n31.02.2026\nMERE ROSII\n0,500 KG X 8,00 4,00\nTOTAL 4,00', 'a' * 64)
    assert result.spent_on is None
    assert result.lines[0].name == 'MERE ROSII'
    assert result.lines[0].quantity == .5 and result.lines[0].unit == 'kg'


def test_bad_photo_never_changes_inventory(client):
    assert client.post('/v1/receipts/preview', content=b'not an image', headers={'Content-Type': 'image/jpeg'}).status_code == 422
    assert snap(client)['receipts'] == [] and snap(client)['pantry'] == []
    with pytest.raises(ValueError):
        recognize_receipt(b'')


def test_receipt_and_expense_import_are_atomic_and_duplicate_photo_is_blocked(client):
    receipt_id, expense_id = str(uuid4()), str(uuid4())
    receipt = {'id': receipt_id, 'image_sha256': 'a' * 64, 'merchant': 'Test', 'spent_on': '2026-11-01', 'total_cents': 1234,
               'expense_id': expense_id, 'imported_at': '2026-11-01T12:00:00Z', 'lines': []}
    expense = {'id': expense_id, 'label': 'Test', 'spent_on': '2026-11-01', 'amount_cents': 1234}
    request_id = uuid4()
    changes = [change('receipts', None, receipt), change('expenses', None, expense)]
    assert action(client, changes, action_id=request_id).status_code == 200
    assert action(client, changes, action_id=request_id).json()['replayed']
    assert action(client, [change('receipts', None, {**receipt, 'id': str(uuid4())}), change('expenses', None, {**expense, 'id': str(uuid4())})]).status_code == 409
    assert len(snap(client)['expenses']) == 1


def test_personal_recipe_is_selectable_and_edits_invalidate_choice(client):
    own = personal()
    item = PantryItem(id=uuid4(), name='Quinoa', quantity=300, unit='g')
    assert action(client, [change('personal_recipes', None, own.model_dump(mode='json')), change('pantry', None, item.model_dump(mode='json'))]).status_code == 200
    request = {'max_minutes': 20, 'equipment': ['plita', 'oala'], 'diet': 'vegan', 'pantry_only': True}
    options = client.post('/v1/recipes/options', json=request).json()
    assert options['options'][0]['id'] == f'personal-{own.id}'
    choice = {'request': request, 'fingerprint': options['fingerprint'], 'recipe_id': options['options'][0]['id'], 'id': str(uuid4()), 'generation_ms': 1}
    result = client.post('/v1/recipes/choose', json=choice)
    assert result.status_code == 200
    assert result.json()['steps'] == own.steps
    changed = own.model_copy(update={'title': 'Rețeta editată'})
    assert action(client, [change('personal_recipes', own.model_dump(mode='json'), changed.model_dump(mode='json'))]).status_code == 200
    assert client.post('/v1/recipes/choose', json=choice).status_code == 409


def test_personal_recipe_requires_unambiguous_quantities_and_distinct_steps(client):
    own = personal().model_dump(mode='json')
    for value in [{**own, 'ingredients': [{'name': 'Quinoa', 'quantity': '0 g'}]}, {**own, 'steps': ['Fierbe.', 'Fierbe.']}]:
        assert action(client, [change('personal_recipes', None, value)]).status_code == 422


def test_restore_is_atomic_creates_safety_copy_and_blocks_old_epochs(client):
    item = PantryItem(id=uuid4(), name='Lapte', quantity=1, unit='l').model_dump(mode='json')
    old_epoch = snap(client)['epoch']
    assert action(client, [change('pantry', None, item)], old_epoch).status_code == 200
    backup = client.post('/v1/backups').raise_for_status().json()
    edited = {**item, 'quantity': 2}
    assert action(client, [change('pantry', item, edited)], old_epoch).status_code == 200
    preview = client.post(f"/v1/backups/{backup['id']}/preview").json()
    request = {'request_id': str(uuid4()), 'expected_fingerprint': preview['fingerprint'], 'backup_sha256': backup['sha256'], 'confirmation': 'RESTAUREAZĂ'}
    restored = client.post(f"/v1/backups/{backup['id']}/restore", json=request).raise_for_status().json()
    current = snap(client)
    assert current['pantry'] == [item]
    assert current['epoch'] != old_epoch and current['restored_at']
    assert restored['safety_backup_id'] != backup['id']
    assert any(entry['kind'] == 'pre_restore' for entry in client.get('/v1/backups').json()['backups'])
    changes = [change('pantry', item, edited)]
    assert action(client, changes, old_epoch).status_code == 409
    assert action(client, changes).status_code == 409
    assert action(client, changes, current['epoch']).status_code == 200
    assert client.post(f"/v1/backups/{backup['id']}/restore", json=request).json() == restored
    assert snap(client)['pantry'] == [edited] # A retry cannot restore a second time.
    assert client.put(f"/v1/pantry/{item['id']}", json={'name': 'Lapte', 'quantity': 9, 'unit': 'l'}).status_code == 409


def test_restore_preview_cannot_overwrite_changes_made_after_preview(client):
    backup = client.post('/v1/backups').json()
    preview = client.post(f"/v1/backups/{backup['id']}/preview").json()
    item = PantryItem(id=uuid4(), name='Ouă', quantity=6).model_dump(mode='json')
    action(client, [change('pantry', None, item)])
    request = {'request_id': str(uuid4()), 'expected_fingerprint': preview['fingerprint'], 'backup_sha256': backup['sha256'], 'confirmation': 'RESTAUREAZĂ'}
    assert client.post(f"/v1/backups/{backup['id']}/restore", json=request).status_code == 409
    assert snap(client)['pantry'] == [item]


def test_daily_schedule_retention_and_unmanaged_files(tmp_path):
    manager = BackupManager(Database(tmp_path / 'app.db'), tmp_path / 'copies')
    manager.configure(BackupSettings(keep_daily=3))
    now = datetime.now(ZoneInfo('Europe/Bucharest'))
    assert manager.maybe_auto(now.replace(hour=2, minute=59)) is None
    assert manager.maybe_auto(now.replace(hour=3, minute=0)) is not None
    assert manager.maybe_auto(now.replace(hour=4)) is None
    unmanaged = manager.directory / 'my-important-file.db'
    unmanaged.write_bytes(b'keep')
    for _ in range(5):
        manager.create('auto')
    assert len([entry for entry in manager.list() if entry.kind == 'auto']) == 3
    assert unmanaged.read_bytes() == b'keep'
    manager.configure(BackupSettings(enabled=False))
    assert manager.maybe_auto(now + timedelta(days=1)) is None


def test_corrupted_backup_is_refused_before_restore(tmp_path):
    manager = BackupManager(Database(tmp_path / 'app.db'), tmp_path / 'copies')
    info = manager.create()
    preview = manager.preview(info.id)
    with (manager.directory / f'{info.id}.db').open('ab') as output:
        output.write(b'corruption')
    request = RestoreRequest(request_id=uuid4(), expected_fingerprint=preview['fingerprint'], backup_sha256=info.sha256, confirmation='RESTAUREAZĂ')
    with pytest.raises(ValueError, match='SHA-256'):
        manager.restore(info.id, request)
    assert not [entry for entry in manager.list() if entry.kind == 'pre_restore']


def test_restore_and_retry_record_commit_together(tmp_path, monkeypatch):
    db = Database(tmp_path / 'app.db')
    manager = BackupManager(db, tmp_path / 'copies')
    backup = manager.create()
    item = PantryItem(id=uuid4(), name='Lapte', quantity=1, unit='l')
    db.put('pantry', item)
    current = db.snapshot()
    request = RestoreRequest(request_id=uuid4(), expected_fingerprint=manager.preview(backup.id)['fingerprint'],
                             backup_sha256=backup.sha256, confirmation='RESTAUREAZĂ')
    with db.connection() as connection:
        connection.execute("""CREATE TRIGGER refuse_restore_result BEFORE INSERT ON metadata
                              WHEN NEW.key LIKE 'restore:%'
                              BEGIN SELECT RAISE(ABORT, 'retry result unavailable'); END""")
    with pytest.raises(sqlite3.IntegrityError, match='retry result unavailable'):
        manager.restore(backup.id, request)
    assert db.snapshot() == current
    with db.connection() as connection:
        connection.execute('DROP TRIGGER refuse_restore_result')

    write_json = manager.atomic_json

    def no_restore_sidecar(name, data):
        if name.startswith('restore-'):
            raise OSError('sidecar unavailable')
        write_json(name, data)

    monkeypatch.setattr(manager, 'atomic_json', no_restore_sidecar)
    result = manager.restore(backup.id, request)
    db.put('pantry', item)
    reopened = BackupManager(Database(db.path), manager.directory)
    assert reopened.restore(backup.id, request) == result
    assert db.snapshot().pantry == [item]


def test_receipt_does_not_guess_ambiguous_alias_or_import_negative_line():
    first = Product(id=uuid4(), name='Orez alb', package_quantity=500, unit='g', receipt_names=['OREZ TEST'])
    second = Product(id=uuid4(), name='Orez brun', package_quantity=1, unit='kg', receipt_names=['OREZ TEST'])
    result = parse_receipt('MAGAZIN\nOREZ TEST 5,00\nCARDAMOM 2,00\nREDUCERE -1,00\nTOTAL 6,00', 'a' * 64, [first, second])
    assert [(line.name, line.quantity, line.unit) for line in result.lines] == [
        ('OREZ TEST', 1.0, 'buc'), ('CARDAMOM', 1.0, 'buc')
    ]
    assert result.lines[0].warnings
