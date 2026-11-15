"""Benchmark the full and conditional snapshots against an isolated synthetic database."""
import argparse
import os
from pathlib import Path
import statistics
from tempfile import TemporaryDirectory
import time
from uuid import uuid4

from fastapi.testclient import TestClient

from app.database import Database
from app.main import create_app
from app.models import PantryItem


def measure(client, headers, repetitions, expected_status, prepare=None):
    timings = []
    sizes = []
    for index in range(repetitions):
        if prepare:
            prepare(index)
        start = time.perf_counter()
        response = client.get("/v1/snapshot", headers=headers)
        timings.append((time.perf_counter() - start) * 1000)
        if response.status_code != expected_status:
            raise RuntimeError(f"HTTP {response.status_code}; expected {expected_status}")
        sizes.append(len(response.content))
    p95 = statistics.quantiles(timings, n=20, method="inclusive")[18]
    return p95, max(sizes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=5000)
    parser.add_argument("--iterations", type=int, default=30)
    args = parser.parse_args()
    if args.rows < 1 or args.iterations < 2:
        parser.error("At least one row and two iterations are required.")
    key = "synthetic-benchmark-not-a-real-api-key"
    os.environ.pop("CAMARA_BACKUP_DIR", None)
    with TemporaryDirectory() as folder:
        path = Path(folder) / "benchmark.db"
        db = Database(path)
        with db.write() as connection:
            rows = (PantryItem(id=uuid4(), name=f"Aliment de probă {index}", quantity=100, unit="g")
                    for index in range(args.rows))
            connection.executemany("INSERT INTO pantry (id,data) VALUES (?,?)",
                                   ((str(row.id), row.model_dump_json()) for row in rows))
        app = create_app(path, key, object())
        with TestClient(app, headers={"Authorization": f"Bearer {key}"}) as client:
            client.put("/v1/backups/settings", json={"enabled": False}).raise_for_status()
            tag = client.get("/v1/snapshot").raise_for_status().headers["ETag"]
            for label, headers, status in [
                ("Full", {}, 200), ("Unchanged", {"If-None-Match": tag}, 304)
            ]:
                p95, size = measure(client, headers, args.iterations, status)
                print(f"{label}: p95 {p95:.1f} ms, {size:,} B response body ({args.rows:,} rows)")
            with db.readonly_connection() as connection:
                raw = connection.execute("SELECT data FROM pantry LIMIT 1").fetchone()[0]
            item = PantryItem.model_validate_json(raw)
            p95, size = measure(client, {}, args.iterations, 200,
                                lambda index: db.put("pantry", item.model_copy(update={"quantity": index + 1})))
            print(f"Changed: p95 {p95:.1f} ms, {size:,} B response body ({args.rows:,} rows)")


if __name__ == "__main__":
    main()
