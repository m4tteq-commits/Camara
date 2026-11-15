from uuid import uuid4

from fastapi.testclient import TestClient

from app.database import Database
from app.main import create_app
from app.models import Expense
from test_api import HEADERS, KEY


def test_history_pages_stay_bounded_and_continue_after_new_entries(tmp_path):
    path = tmp_path / "history.db"
    db = Database(path)

    def save(amount):
        item = Expense(id=uuid4(), label="Probă", amount_cents=amount, spent_on="2026-11-01")
        db.put("expenses", item)
        return str(item.id)

    existing = [save(amount) for amount in range(1, 6)]
    path_url = "/v1/history/expenses"
    with TestClient(create_app(path, KEY, object()), headers=HEADERS) as client:
        assert client.get(path_url, headers={"Authorization": ""}).status_code == 401
        first = client.get(path_url, params={"limit": 2}).raise_for_status().json()
        assert [entry["id"] for entry in first["items"]] == list(reversed(existing[-2:]))
        new = save(10)
        cursor = first["next_cursor"]
        second = client.get(path_url, params={"cursor": cursor, "limit": 2}).raise_for_status().json()
        assert [entry["id"] for entry in second["items"]] == list(reversed(existing[1:3]))
        final = client.get(path_url, params={"cursor": second["next_cursor"], "limit": 2}).raise_for_status().json()
        assert [entry["id"] for entry in final["items"]] == existing[:1]
        assert final["next_cursor"] is None
        assert client.get(path_url, params={"limit": 1}).json()["items"][0]["id"] == new
        assert client.get(path_url, params={"limit": 101}).status_code == 422
        assert client.get(path_url, params={"cursor": "bad-cursor"}).status_code == 422
        assert client.get("/v1/history/purchases", params={"cursor": cursor}).status_code == 422
        assert client.get("/v1/history/pantry").status_code == 422
        with db.write() as connection:
            connection.execute("UPDATE metadata SET value=? WHERE key='epoch'", (str(uuid4()),))
        assert client.get(path_url, params={"cursor": cursor}).status_code == 409
        assert len(client.get("/v1/snapshot").json()["expenses"]) == 6


def test_snapshot_delta_leaves_history_unchanged_and_can_remove_last_record(tmp_path):
    path = tmp_path / "delta.db"
    db = Database(path)
    for _ in range(25):
        db.put("expenses", Expense(id=uuid4(), label="Probă", amount_cents=100, spent_on="2026-11-01"))
    with TestClient(create_app(path, KEY, object()), headers=HEADERS) as client:
        endpoint = "/v1/snapshot"
        initial = client.get(endpoint).raise_for_status()
        old = initial.headers["ETag"]
        item_id = uuid4()
        client.put(f"/v1/pantry/{item_id}", json={"name": "Ouă", "quantity": 6}).raise_for_status()
        delta = client.get(endpoint, params={"since": old}, headers={"If-None-Match": old}).raise_for_status()
        assert delta.headers["X-Camara-Snapshot-Mode"] == "delta"
        assert delta.json()["base_etag"] == old
        assert set(delta.json()["changes"]) == {"pantry", "collection_revisions"}
        full = client.get(endpoint).raise_for_status()
        assert initial.json() | delta.json()["changes"] == full.json()
        assert len(delta.content) < len(full.content) / 2
        db.delete("pantry", item_id)
        removed = client.get(endpoint, params={"since": full.headers["ETag"]}).raise_for_status()
        assert removed.json()["changes"]["pantry"] == []
        unknown = client.get(endpoint, params={"since": '"unknown"'}).raise_for_status()
        assert "X-Camara-Snapshot-Mode" not in unknown.headers
        assert unknown.json()["expenses"] == full.json()["expenses"]
        with db.write() as connection:
            connection.execute("UPDATE metadata SET value=? WHERE key='epoch'", (str(uuid4()),))
        restored = client.get(endpoint, params={"since": full.headers["ETag"]}).raise_for_status()
        assert "X-Camara-Snapshot-Mode" not in restored.headers and restored.json()["epoch"] != full.json()["epoch"]
