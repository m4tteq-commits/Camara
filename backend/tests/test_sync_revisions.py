import time
from uuid import UUID, uuid4

from fastapi.testclient import TestClient

from app.database import CLOCK_LEEWAY, REPLAY_SECONDS, Database
from app.main import create_app
from test_api import HEADERS, KEY


def test_compact_guard_uses_only_its_collection_and_retries_safely(tmp_path):
    with TestClient(create_app(tmp_path / "guard.db", KEY, object()), headers=HEADERS) as client:
        snapshot = client.get("/v1/snapshot").json()
        client.put(f"/v1/shopping/{uuid4()}", json={"name": "Lapte"}).raise_for_status()
        row_id = str(uuid4())
        action_id = uuid4()
        action = {"label": "Calcul", "epoch": snapshot["epoch"],
                  "changes": [{"kind": "shopping", "id": row_id, "after": {"id": row_id, "name": "Pâine"}}],
                  "guard_revisions": {"pantry": snapshot["collection_revisions"]["pantry"]}}
        client.put(f"/v1/actions/{action_id}", json=action).raise_for_status()
        client.put(f"/v1/pantry/{uuid4()}", json={"name": "Ouă", "quantity": 3}).raise_for_status()
        assert client.put(f"/v1/actions/{action_id}", json=action).json()["replayed"]
        assert client.put(f"/v1/actions/{uuid4()}", json=action).status_code == 409


def test_only_expired_replay_keys_are_collected_and_cannot_be_resubmitted(tmp_path):
    path = tmp_path / "retention.db"
    db = Database(path)
    now = time.time()
    old = now - REPLAY_SECONDS - CLOCK_LEEWAY - 100
    salt = uuid4().int & ((1 << 64) - 1)
    expired = UUID(int=(int(old * 1000) << 80) | (7 << 76) | salt)
    legacy = uuid4()
    with db.connection() as connection:
        connection.executemany("INSERT INTO mutations (id,digest,created) VALUES (?,'test',?)",
                               [(str(expired), old), (str(legacy), old)])
    db.collect_stale(now)
    with db.readonly_connection() as connection:
        assert connection.execute("SELECT id FROM mutations").fetchall() == [(str(legacy),)]
    with TestClient(create_app(path, KEY, object()), headers=HEADERS) as client:
        item = str(uuid4())
        action = {"label": "Calcul", "changes": [{"kind": "shopping", "id": item, "after": {"id": item, "name": "Pâine"}}]}
        assert client.put(f"/v1/actions/{expired}", json=action).status_code == 409
        assert not client.get("/v1/snapshot").json()["shopping"]
