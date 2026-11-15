import asyncio
import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.ai import OllamaProvider
from app.main import create_app
from app.models import PantryItem, RecipeRequest


@pytest.fixture
def anyio_backend():
    return "asyncio"


class Clock:
    value = 1000.0

    def __call__(self):
        return self.value


def transport(calls):
    def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        return httpx.Response(200, json={"done": True, "response": '{"recipe_id":"omleta"}'})
    return httpx.MockTransport(respond)


@pytest.mark.anyio
async def test_startup_stays_idle_and_status_never_loads_a_model():
    calls = []
    def respond(request):
        calls.append((request.method, request.url.path))
        return httpx.Response(200, json={"models": [{"name": "test"}]} if request.url.path == "/api/tags" else {"models": []})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        ai = OllamaProvider("http://local", "test", client)
        await ai.reconcile()
        status = await ai.status()
        assert status.available and "repaus" in status.detail
        assert calls == [("POST", "/api/generate"), ("GET", "/api/tags"), ("GET", "/api/ps")]
        calls.clear()
        await ai.reconcile()
        assert calls == []


@pytest.mark.anyio
async def test_two_devices_only_unload_after_the_last_one_leaves():
    clock, calls = Clock(), []
    async with httpx.AsyncClient(transport=transport(calls)) as client:
        ai = OllamaProvider("http://local", "test", client, clock=clock)
        ai.touch("phone")
        ai.touch("tablet")
        await ai.reconcile()
        assert calls[-1]["keep_alive"] == "90s" and "prompt" not in calls[-1]
        ai.release("phone")
        clock.value += 20
        await ai.reconcile()
        assert ai.activity()["active_clients"] == 1
        assert len(calls) == 1
        ai.release("tablet")
        clock.value += 14
        await ai.reconcile()
        assert len(calls) == 1
        clock.value += 1
        await ai.reconcile()
        assert calls[-1]["keep_alive"] == 0
        assert ai.unloaded


@pytest.mark.anyio
async def test_reopening_during_grace_avoids_reload_and_heartbeat_refreshes_ram():
    clock, calls = Clock(), []
    async with httpx.AsyncClient(transport=transport(calls)) as client:
        ai = OllamaProvider("http://local", "test", client, clock=clock)
        ai.touch("phone")
        await ai.reconcile()
        ai.release("phone")
        clock.value += 10
        ai.touch("phone")
        await ai.reconcile()
        assert len(calls) == 1
        clock.value += 21
        ai.touch("phone")
        await ai.reconcile()
        assert [call["keep_alive"] for call in calls] == ["90s", "90s"]
        clock.value += 50  # The heartbeat renewed the lease beyond the original deadline.
        assert ai.activity()["active_clients"] == 1


@pytest.mark.anyio
async def test_lost_wifi_or_killed_app_expires_without_a_release_message():
    clock, calls = Clock(), []
    async with httpx.AsyncClient(transport=transport(calls)) as client:
        ai = OllamaProvider("http://local", "test", client, clock=clock)
        ai.touch("phone")
        await ai.reconcile()
        clock.value += 75
        await ai.reconcile()
        assert ai.activity()["active_clients"] == 0
        assert len(calls) == 1
        clock.value += 15
        await ai.reconcile()
        assert calls[-1]["keep_alive"] == 0
        ai.release("unknown")
        ai.release("phone")
        await ai.reconcile()
        assert len(calls) == 2


@pytest.mark.anyio
async def test_unload_waits_for_inference_to_finish():
    clock, calls = Clock(), []
    started, finish = asyncio.Event(), asyncio.Event()
    async def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        if "prompt" in body:
            started.set()
            await finish.wait()
        return httpx.Response(200, json={"done": True, "response": '{"recipe_id":"omleta"}'})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        ai = OllamaProvider("http://local", "test", client, clock=clock)
        ai.touch("phone")
        generating = asyncio.create_task(ai.generate(RecipeRequest(), [PantryItem(id=uuid4(), name="Ouă", quantity=2)]))
        await started.wait()
        ai.release("phone")
        clock.value += 20
        unloading = asyncio.create_task(ai.reconcile())
        await asyncio.sleep(0)
        assert not unloading.done() and len(calls) == 1
        finish.set()
        recipe = await generating
        await unloading
        assert recipe.title and calls[0]["keep_alive"] == "90s"
        assert calls[1]["keep_alive"] == 0


@pytest.mark.anyio
async def test_successful_inference_extends_memory_without_an_extra_preload():
    clock, calls = Clock(), []
    async with httpx.AsyncClient(transport=transport(calls)) as client:
        ai = OllamaProvider("http://local", "test", client, clock=clock)
        ai.touch("phone")
        await ai.generate(RecipeRequest(), [PantryItem(id=uuid4(), name="Ouă", quantity=2)])
        assert len(calls) == 1 and "prompt" in calls[0]
        clock.value += 29
        await ai.reconcile()
        assert len(calls) == 1
        clock.value += 1
        await ai.reconcile()
        assert len(calls) == 2 and "prompt" not in calls[1]


@pytest.mark.anyio
async def test_failed_unload_is_retried():
    calls = []
    def respond(request):
        calls.append(json.loads(request.content))
        return httpx.Response(503 if len(calls) == 1 else 200, json={"done": True})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        ai = OllamaProvider("http://local", "test", client)
        with pytest.raises(httpx.HTTPStatusError):
            await ai.reconcile()
        assert not ai.unloaded
        await ai.reconcile()
        assert ai.unloaded and len(calls) == 2


def test_activity_routes_are_authenticated_and_validate_session_ids(tmp_path):
    key = "activity-" + "a" * 40
    # No background/network worker is required to verify the lease API contract.
    class LeaseProvider:
        activity = lambda self: {"active_clients": 0}
        touch = lambda self, session: {"active_clients": 1}
        release = lambda self, session: {"active_clients": 0}
    with TestClient(create_app(tmp_path / "activity.db", key, LeaseProvider())) as client:
        path = f"/v1/ai/sessions/{uuid4()}"
        assert client.put(path).status_code == 401
        assert client.delete(path).status_code == 401
        assert client.get("/v1/ai/activity").status_code == 401
        headers = {"Authorization": "Bearer " + key}
        assert client.put(path, headers=headers).json()["active_clients"] == 1
        assert client.delete(path, headers=headers).json()["active_clients"] == 0
        assert client.put("/v1/ai/sessions/invalid", headers=headers).status_code == 422
