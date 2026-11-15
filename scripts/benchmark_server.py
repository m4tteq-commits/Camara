"""Repeat one real API request four times, reporting selection cache use explicitly."""
import os
import time
from urllib.parse import urlparse

import httpx


def main():
    url = os.environ.get("CAMARA_BENCHMARK_URL", "").rstrip("/")
    key = os.environ.get("CAMARA_API_KEY", "")
    if urlparse(url).scheme not in {"http", "https"} or not key:
        raise SystemExit("Setează CAMARA_BENCHMARK_URL și CAMARA_API_KEY în mediul procesului.")
    times = []
    with httpx.Client(base_url=url, headers={"Authorization": f"Bearer {key}"}, timeout=260, trust_env=False) as client:
        status = client.get("/v1/ai/status")
        status.raise_for_status()
        if not status.json()["available"]:
            raise SystemExit("Modelul nu este pregătit pe server.")
        for index in range(4):
            started = time.perf_counter()
            result = client.post("/v1/recipes/generate", json={"max_minutes": 30, "servings": 1})
            elapsed = time.perf_counter() - started
            if result.is_success:
                times.append(elapsed)
                recipe = result.json()
                print(f"Proba {index + 1}: {elapsed:.3f}s, cache={recipe['selection_cached']}, id={recipe['id']}")
            else:
                print(f"Proba {index + 1}: eroare HTTP {result.status_code}, {elapsed:.1f}s")
                raise SystemExit("Oprește benchmarkul și verifică serverul înainte de reluare.")
    print("Cererile din cache nu măsoară inferența. Aceeași recomandare poate fi reutilizată.")


if __name__ == "__main__":
    main()
