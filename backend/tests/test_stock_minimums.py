from datetime import date, timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import create_app


def test_minimum_is_shared_and_duplicate_alias_cannot_be_created(tmp_path):
    client = TestClient(create_app(tmp_path / "test.db", "a" * 40))
    client.headers["Authorization"] = "Bearer " + "a" * 40
    snapshot = client.get("/v1/snapshot").json()
    assert snapshot["stock_minimums"] == []

    def add(name):
        minimum = {"id": str(uuid4()), "name": name, "quantity": 1, "unit": "l"}
        action = {"label": "Prag de stoc", "epoch": snapshot["epoch"],
                  "changes": [{"kind": "stock_minimums", "id": minimum["id"], "after": minimum}]}
        return client.put(f"/v1/actions/{uuid4()}", json=action), minimum

    result, minimum = add("Lapte")
    assert result.status_code == 200
    assert add("Lapte UHT")[0].status_code == 409
    assert client.get("/v1/snapshot").json()["stock_minimums"] == [minimum]
    assert client.post("/v1/backups").json()["counts"]["stock_minimums"] == 1


def test_use_soon_recipes_require_valid_matching_stock_and_do_not_save_recipes(tmp_path):
    client = TestClient(create_app(tmp_path / "test.db", "a" * 40))
    assert client.get("/v1/recipes/use-soon").status_code == 401
    client.headers["Authorization"] = "Bearer " + "a" * 40
    today = date.today()
    rows = [
        ("Ouă de găină", 4, "buc", today),
        ("Lapte", 1, "l", today - timedelta(days=1)),
        ("Roșii", 2, "g", today + timedelta(days=1)),
        ("Banane", 2, "buc", today + timedelta(days=4)),
    ]
    for name, quantity, unit, expiry in rows:
        response = client.put(f"/v1/pantry/{uuid4()}", json={"name": name, "quantity": quantity, "unit": unit, "expires_on": expiry.isoformat()})
        assert response.status_code == 200

    ideas = client.get("/v1/recipes/use-soon").json()
    assert ideas and all(idea["foods"] == ["Ouă de găină"] for idea in ideas)
    assert client.get("/v1/snapshot").json()["recipes"] == []


def test_quick_use_and_inventory_update_share_a_transaction(tmp_path):
    client = TestClient(create_app(tmp_path / "test.db", "a" * 40))
    client.headers["Authorization"] = "Bearer " + "a" * 40
    item_id = str(uuid4())
    item = client.put(f"/v1/pantry/{item_id}", json={"name": "Ouă", "quantity": 6}).json()
    snapshot = client.get("/v1/snapshot").json()
    event_id = str(uuid4())
    event = {"id": event_id, "kind": "used", "title": "Ouă", "occurred_on": date.today().isoformat(),
             "allocations": [{"item": item, "quantity": 2}]}
    action = {"label": "Consum rapid", "epoch": snapshot["epoch"], "changes": [
        {"kind": "pantry", "id": item_id, "before": item, "after": {**item, "quantity": 4}},
        {"kind": "events", "id": event_id, "after": event},
    ]}
    endpoint = f"/v1/actions/{uuid4()}"
    assert client.put(endpoint, json=action).status_code == 200
    assert client.put(endpoint, json=action).json()["replayed"]
    current = client.get("/v1/snapshot").json()
    assert current["pantry"][0]["quantity"] == 4
    assert [entry["kind"] for entry in current["events"]] == ["used"]
