"""Bound work before FastAPI parses request bodies. One API worker per household."""
import asyncio
import os
import secrets
import time
from collections import OrderedDict, deque
from pathlib import Path

from fastapi import HTTPException
from starlette.responses import JSONResponse


def secret_setting(name):
    filename = os.environ.get(name + "_FILE")
    return Path(filename).read_text(encoding="utf-8").strip() if filename else os.environ.get(name, "").strip()


class Windows:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.entries = OrderedDict()

    def allow(self, key, limit, seconds):
        now = self.clock()
        times = self.entries.setdefault(key, deque())
        self.entries.move_to_end(key)
        while times and times[0] <= now - seconds:
            times.popleft()
        if len(times) >= limit:
            return False
        times.append(now)
        while len(self.entries) > 2048:
            self.entries.popitem(last=False)
        return True


class RequestLimits:
    def __init__(self, app, keys):
        self.app, self.keys = app, keys
        self.windows = Windows()
        self.ai_hourly = int(os.environ.get("CAMARA_AI_REQUESTS_PER_HOUR", "120"))
        self.ai_daily = int(os.environ.get("CAMARA_AI_REQUESTS_PER_DAY", "500"))

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path, method = scope["path"], scope["method"]
        async def reject(code, detail):
            headers = {"Retry-After": "60"} if code == 429 else {}
            if code == 401:
                headers["WWW-Authenticate"] = "Bearer"
            await JSONResponse({"detail": detail}, status_code=code, headers=headers)(scope, receive, send)

        if path == "/livez" and method in {"GET", "HEAD"}:
            return await JSONResponse({"ok": True})(scope, receive, send)
        headers = dict(scope["headers"])
        if path == "/health" or path.startswith("/v1/"):
            auth = headers.get(b"authorization", b"").split(None, 1)
            token = auth[1] if len(auth) == 2 and auth[0].lower() == b"bearer" else b""
            valid = any(secrets.compare_digest(token, key.encode()) for key in self.keys)
            if not valid:
                address = (scope.get("client") or ("unknown",))[0]
                allowed = self.windows.allow(("auth", address), 20, 60)
                return await reject(401 if allowed else 429, "Cheie de acces invalidă." if allowed else "Prea multe încercări. Așteaptă un minut.")
            if not self.windows.allow("requests", 1200, 60):
                return await reject(429, "Prea multe cereri. Reîncearcă într-un minut.")
            expensive = method == "POST" and (path.startswith("/v1/recipes/cloud/") or path in {
                "/v1/recipes/generate", "/v1/plans/preview", "/v1/personal-recipes/import-preview",
                "/v1/personal-recipes/pdf-preview"} or path.endswith(("/advice", "/conversation")) or
                path.startswith("/v1/personal-recipes/pdf-uploads/") and path.endswith("/preview"))
            if expensive and (not self.windows.allow("ai-hour", self.ai_hourly, 3600) or
                              not self.windows.allow("ai-day", self.ai_daily, 86400)):
                return await reject(429, "Bugetul de cereri AI al serverului a fost atins. Încearcă mai târziu.")
        if method not in {"POST", "PUT", "PATCH"}:
            return await self.app(scope, receive, send)
        limit = 2 * 1024 * 1024
        if path in {"/v1/personal-recipes/pdf-uploads", "/v1/personal-recipes/pdf-preview"}:
            limit = 20 * 1024 * 1024
        elif path in {"/v1/receipts/preview", "/v1/personal-recipes/photo-text"}:
            limit = 12_000_000
        try:
            length = int(headers.get(b"content-length", b"0"))
        except ValueError:
            return await reject(400, "Dimensiunea cererii nu este validă.")
        if length < 0 or length > limit:
            return await reject(413, "Fișierul sau cererea depășește dimensiunea permisă.")
        size, started = 0, time.monotonic()
        async def bounded_receive():
            nonlocal size
            remaining = 30 - (time.monotonic() - started)
            try:
                message = await asyncio.wait_for(receive(), timeout=max(0.001, remaining))
            except TimeoutError as exc:
                raise HTTPException(408, "Încărcarea a durat prea mult. Reîncearcă.") from exc
            size += len(message.get("body", b""))
            if size > limit:
                raise HTTPException(413, "Fișierul sau cererea depășește dimensiunea permisă.")
            return message
        await self.app(scope, bounded_receive, send)
