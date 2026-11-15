import hashlib
import os
import secrets
import asyncio
import sqlite3
from contextlib import suppress
from contextlib import AsyncExitStack, asynccontextmanager
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Annotated
from uuid import UUID, uuid4
from datetime import datetime, timezone

import httpx
from anyio import to_thread
from fastapi import Depends, FastAPI, HTTPException, Response, Security, Request, Header, Query
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.responses import FileResponse, JSONResponse

from .ai import AiError, OllamaProvider, recipe_expiry, selection_context
from .catalog import quick_suggestions
from .cloud_ai import CloudProviderId, CloudRecipeProvider, PROVIDERS
from .bulk import preview
from .database import BatchConflict, Database, SCHEMA_VERSION
from .backups import BackupManager, BackupSettings, RestoreRequest
from .receipts import recognize_receipt
from .recipe_preferences import PreferenceConfirmation
from .personal_recipe_ai import (ImportedRecipe, RecipeAdvice, RecipeAdviceRequest, RecipeImportText, advice_input,
                                 draft_from_text, get_advice, with_cloud_backup)
from .recipe_pdf import (PDF_MAX_BYTES, PDF_MAX_PAGES, PdfRecipes, PdfSessions, PdfUpload, drafts_from_pdf,
                         page_text, pdf_pages, session_page_text)
from .weekly import WeekRequest, ai_options, preview as week_preview
from .use_soon import use_soon
from .models import (AiStatus, BatchResult, BulkPreview, BulkTextRequest, DeviceChoice, DeviceModelInfo,
                     DeviceOptions, PantryBatch, PantryInput, PantryItem, Recipe, RecipeRequest, ShoppingInput, ShoppingItem, Snapshot,
                     Mutation, MutationResult, ReceiptPreview, UseSoonIdea)

PHONE_MODEL_SHA = "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"
PHONE_MODEL_BYTES = 343490560
API_VERSION = "0.9.7"


def create_app(db_path: Path | None = None, api_key: str | None = None, provider=None) -> FastAPI:
    key = api_key if api_key is not None else os.environ.get("CAMARA_API_KEY", "")
    if len(key) < 32:
        raise RuntimeError("Setează CAMARA_API_KEY la o valoare aleatoare de cel puțin 32 de caractere.")
    db = Database(db_path or Path(os.environ.get("CAMARA_DB_PATH", "/data/camara.db")))
    backups = BackupManager(db, Path(os.environ.get("CAMARA_BACKUP_DIR", str(db.path.parent / 'backups'))))
    ocr_gate = asyncio.Semaphore(1)
    pdf_gate = asyncio.Lock()

    @asynccontextmanager
    async def lifespan(app):
        async with AsyncExitStack() as stack:
            client = await stack.enter_async_context(httpx.AsyncClient(trust_env=False))
            app.state.pdf_dir = Path(stack.enter_context(TemporaryDirectory(prefix="camara-pdf-uploads-")))
            app.state.pdf_sessions = PdfSessions()
            app.state.ai = provider or OllamaProvider(
                os.environ.get("OLLAMA_BASE_URL", "http://ollama:11434"),
                os.environ.get("OLLAMA_MODEL", "qwen3.5:2b"), client, db,
            )
            app.state.cloud_ai = {name: CloudRecipeProvider(name, client, db) for name in PROVIDERS}
            async def automatic_backups():
                while True:
                    try:
                        await asyncio.to_thread(backups.maybe_auto)
                    except Exception:
                        backups.last_error = "Backupul automat nu a reușit. Verifică spațiul și accesul la directorul de copii."
                    await asyncio.sleep(60)
            async def expire_pdf_uploads():
                while True:
                    await asyncio.sleep(60)
                    async with pdf_gate:
                        app.state.pdf_sessions.expire()
            task = asyncio.create_task(automatic_backups())
            pdf_task = asyncio.create_task(expire_pdf_uploads())
            ai_task = asyncio.create_task(app.state.ai.manage_activity()) if isinstance(app.state.ai, OllamaProvider) else None
            try:
                yield
            finally:
                task.cancel()
                pdf_task.cancel()
                with suppress(asyncio.CancelledError):
                    await task
                with suppress(asyncio.CancelledError):
                    await pdf_task
                if ai_task is not None:
                    ai_task.cancel()
                    with suppress(asyncio.CancelledError):
                        await ai_task
                    with suppress(httpx.HTTPError):
                        await app.state.ai.unload()

    bearer = HTTPBearer(auto_error=False)

    def authenticate(credentials: HTTPAuthorizationCredentials | None = Security(bearer)):
        if credentials is None or not secrets.compare_digest(credentials.credentials.encode(), key.encode()):
            raise HTTPException(401, "Cheie de acces invalidă.", headers={"WWW-Authenticate": "Bearer"})

    app = FastAPI(title="Cămara · API local", version=API_VERSION, lifespan=lifespan,
                  dependencies=[Depends(authenticate)], docs_url=None, redoc_url=None, openapi_url=None)

    def check_database():
        try:
            with db.readonly_connection() as connection:
                schema = connection.execute("PRAGMA user_version").fetchone()[0]
                epoch = connection.execute("SELECT value FROM metadata WHERE key='epoch'").fetchone()
            if schema != SCHEMA_VERSION or not epoch or not epoch[0]:
                raise sqlite3.DatabaseError("Unexpected database state")
        except sqlite3.Error as exc:
            raise HTTPException(503, "Baza de date nu este disponibilă.") from exc
        return schema

    @app.get("/health")
    def health():
        check_database()
        return {"status": "ok", "version": API_VERSION}

    @app.get("/v1/status")
    def server_status():
        schema = check_database()
        try:
            latest = next(iter(backups.list()), None)
            backup_error = backups.last_error
        except OSError:
            latest = None
            backup_error = "Nu pot verifica directorul de backup."
        return {"status": "ok", "version": API_VERSION, "database": "ready", "schema_version": schema,
                "last_backup_at": latest.created_at if latest else None, "backup_error": backup_error}

    def write_epoch(epoch: str | None = Header(default=None, alias="X-Camara-Epoch")):
        return db.checked_epoch(epoch)

    @app.exception_handler(BatchConflict)
    async def conflict(_request, exc):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.get("/v1/backups")
    def backup_list():
        return {"settings": backups.settings(), "backups": backups.list(), "error": backups.last_error}

    @app.put("/v1/backups/settings")
    def backup_settings(settings: BackupSettings):
        return backups.configure(settings)

    @app.post("/v1/backups")
    def backup_create():
        return backups.create()

    @app.post("/v1/backups/{backup_id}/preview")
    def backup_preview(backup_id: UUID):
        try:
            return backups.preview(backup_id)
        except FileNotFoundError as exc:
            raise HTTPException(404, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, "Copia nu poate fi folosită.") from exc

    @app.post("/v1/backups/{backup_id}/restore")
    def backup_restore(backup_id: UUID, request: RestoreRequest):
        try:
            return backups.restore(backup_id, request)
        except FileNotFoundError as exc:
            raise HTTPException(404, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.post("/v1/receipts/preview", response_model=ReceiptPreview)
    async def receipt_preview(request: Request):
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > 12_000_000:
                raise HTTPException(413, "Fotografia depășește 12 MB.")
        if ocr_gate.locked():
            raise HTTPException(429, "Un alt bon este în curs de citire. Reîncearcă în câteva momente.")
        async with ocr_gate:
            try:
                return await asyncio.to_thread(lambda: recognize_receipt(bytes(data), db.records("products")))
            except ValueError as exc:
                raise HTTPException(422, str(exc)) from exc
            except RuntimeError as exc:
                raise HTTPException(503, str(exc)) from exc

    @app.get("/v1/snapshot", response_model=Snapshot)
    def snapshot():
        return db.snapshot()

    @app.put("/v1/actions/{action_id}", response_model=MutationResult)
    def apply_action(action_id: UUID, request: Mutation):
        try:
            return db.mutate(action_id, request)
        except ValueError as exc:
            raise HTTPException(422, "Verifică datele acțiunii.") from exc

    @app.post("/v1/pantry/preview", response_model=BulkPreview)
    def bulk_preview(request: BulkTextRequest):
        try:
            return preview(request.text)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.put("/v1/pantry/batches/{batch_id}", response_model=BatchResult)
    def bulk_import(batch_id: UUID, request: PantryBatch, epoch: str = Depends(write_epoch)):
        return db.import_batch(batch_id, request, epoch=epoch)

    @app.put("/v1/pantry/{item_id}", response_model=PantryItem)
    def put_pantry(item_id: UUID, item: PantryInput, epoch: str = Depends(write_epoch)):
        return db.put("pantry", PantryItem(id=item_id, **item.model_dump()), epoch=epoch)

    @app.delete("/v1/pantry/{item_id}", status_code=204)
    def delete_pantry(item_id: UUID, epoch: str = Depends(write_epoch)):
        if not db.delete("pantry", item_id, epoch=epoch):
            raise HTTPException(404, "Aliment inexistent.")
        return Response(status_code=204)

    @app.put("/v1/shopping/{item_id}", response_model=ShoppingItem)
    def put_shopping(item_id: UUID, item: ShoppingInput, epoch: str = Depends(write_epoch)):
        return db.put("shopping", ShoppingItem(id=item_id, **item.model_dump()), epoch=epoch)

    @app.delete("/v1/shopping/{item_id}", status_code=204)
    def delete_shopping(item_id: UUID, epoch: str = Depends(write_epoch)):
        if not db.delete("shopping", item_id, epoch=epoch):
            raise HTTPException(404, "Produs inexistent.")
        return Response(status_code=204)

    @app.get("/v1/ai/status", response_model=AiStatus)
    async def ai_status():
        return await app.state.ai.status()

    @app.get("/v1/ai/providers", response_model=dict[str, str])
    async def cloud_providers():
        return {name: provider.model for name, provider in app.state.cloud_ai.items() if provider.configured}

    @app.post('/v1/plans/preview')
    async def automatic_plan(request: WeekRequest):
        snapshot = await asyncio.to_thread(db.snapshot)
        seed, used, note = request.seed, False, ''
        if request.use_ai and not seed:
            try:
                options = await asyncio.to_thread(ai_options, snapshot, request)
                seed = await app.state.ai.plan_choice(options, request.preferences)
                used = bool(seed)
            except (AiError, AttributeError) as exc:
                note = exc.detail if isinstance(exc, AiError) else 'AI nu este disponibil; verifică propunerea calculată.'
            except ValueError as exc:
                raise HTTPException(422, str(exc)) from exc
        elif seed:
            used = True
        try:
            return await asyncio.to_thread(week_preview, snapshot, request, seed, used, note)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.get("/v1/ai/activity")
    async def ai_activity():
        return app.state.ai.activity()

    @app.put("/v1/ai/sessions/{session_id}")
    async def ai_active(session_id: UUID):
        return app.state.ai.touch(str(session_id))

    @app.delete("/v1/ai/sessions/{session_id}")
    async def ai_inactive(session_id: UUID):
        return app.state.ai.release(str(session_id))

    @app.post("/v1/recipes/generate", response_model=Recipe)
    async def generate_recipe(request: RecipeRequest, epoch: str = Depends(write_epoch)):
        try:
            snapshot = await asyncio.to_thread(db.recipe_snapshot)
            ai = app.state.ai
            if isinstance(ai, OllamaProvider):
                recipe = await ai.generate(request, snapshot.pantry, snapshot.personal_recipes)
            else:
                recipe = await ai.generate(request, snapshot.pantry)
            return await asyncio.to_thread(db.save_recommendation, recipe, epoch=epoch)
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    @app.get("/v1/recipes/use-soon", response_model=list[UseSoonIdea])
    def expiring_recipes():
        return use_soon(db.recipe_snapshot())

    @app.post("/v1/recipes/cloud/{provider_id}", response_model=Recipe)
    async def generate_cloud_recipe(provider_id: CloudProviderId, request: RecipeRequest, epoch: str = Depends(write_epoch)):
        try:
            snapshot = await asyncio.to_thread(db.recipe_snapshot)
            if snapshot.epoch != epoch:
                raise BatchConflict("Serverul a fost restaurat. Actualizează aplicația înainte de a cere o recomandare.")
            providers = app.state.cloud_ai
            try:
                recipe = await providers[provider_id].generate(request, snapshot)
            except AiError as exc:
                backup = providers["openrouter"]
                if provider_id != "groq" or not backup.configured or exc.status_code not in {429, 502, 503, 504}:
                    raise
                recipe = await backup.generate(request, snapshot)
            return await asyncio.to_thread(db.save_recommendation, recipe, epoch=epoch)
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    @app.post("/v1/recipes/suggestions", response_model=list[Recipe])
    def recipe_suggestions(request: RecipeRequest):
        snapshot = db.recipe_snapshot()
        try:
            return quick_suggestions(request, snapshot.pantry, snapshot.personal_recipes)
        except PreferenceConfirmation as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.post("/v1/personal-recipes/import-preview", response_model=ImportedRecipe)
    async def personal_recipe_import(request: RecipeImportText):
        try:
            return await with_cloud_backup(app.state.cloud_ai, lambda cloud: draft_from_text(cloud, request.text))
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    def check_pdf_range(first: int, last: int):
        if first > last or last - first >= PDF_MAX_PAGES:
            raise HTTPException(422, f"Alege între 1 și {PDF_MAX_PAGES} pagini consecutive.")

    def check_pdf_available():
        if pdf_gate.locked():
            raise HTTPException(429, "Un PDF este deja în curs de citire. Reîncearcă în câteva momente.")

    async def write_pdf(request: Request, path: Path):
        if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/pdf":
            raise HTTPException(415, "Alege un fișier PDF.")
        size = 0
        with path.open("wb") as file:
            async for chunk in request.stream():
                size += len(chunk)
                if size > PDF_MAX_BYTES:
                    raise HTTPException(413, "PDF-ul depășește 20 MiB.")
                file.write(chunk)
        with path.open("rb") as file:
            if file.read(5) != b"%PDF-":
                raise HTTPException(422, "Fișierul ales nu este un PDF valid.")

    async def pdf_drafts(text: str):
        try:
            return await with_cloud_backup(app.state.cloud_ai, lambda cloud: drafts_from_pdf(cloud, text))
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    @app.post("/v1/personal-recipes/pdf-uploads", response_model=PdfUpload, status_code=201)
    async def upload_personal_recipe_pdf(request: Request):
        check_pdf_available()
        async with pdf_gate:
            path = app.state.pdf_dir / f"{uuid4().hex}.pdf"
            registered = False
            try:
                await write_pdf(request, path)
                pages = await to_thread.run_sync(pdf_pages, path)
                upload = app.state.pdf_sessions.add(path, pages)
                registered = True
                return upload
            except AiError as exc:
                raise HTTPException(exc.status_code, exc.detail) from exc
            finally:
                if not registered:
                    path.unlink(missing_ok=True)

    @app.post("/v1/personal-recipes/pdf-uploads/{upload_id}/preview", response_model=PdfRecipes)
    async def preview_uploaded_recipe_pdf(upload_id: UUID, first: Annotated[int, Query(ge=1, le=10000)],
                                          last: Annotated[int, Query(ge=1, le=10000)]):
        check_pdf_range(first, last)
        check_pdf_available()
        async with pdf_gate:
            try:
                book = app.state.pdf_sessions.get(upload_id)
                text = await to_thread.run_sync(session_page_text, book, first, last)
            except AiError as exc:
                raise HTTPException(exc.status_code, exc.detail) from exc
        return await pdf_drafts(text)

    @app.post("/v1/personal-recipes/pdf-preview", response_model=PdfRecipes)
    async def personal_recipe_pdf(request: Request, first: Annotated[int, Query(ge=1, le=10000)],
                                  last: Annotated[int, Query(ge=1, le=10000)]):
        check_pdf_range(first, last)
        check_pdf_available()
        async with pdf_gate:
            try:
                with TemporaryDirectory(prefix="camara-recipe-pdf-") as folder:
                    path = Path(folder) / "book.pdf"
                    await write_pdf(request, path)
                    text = await to_thread.run_sync(page_text, path, first, last)
            except AiError as exc:
                raise HTTPException(exc.status_code, exc.detail) from exc
        return await pdf_drafts(text)

    @app.post("/v1/personal-recipes/{recipe_id}/advice", response_model=RecipeAdvice)
    async def personal_recipe_advice(recipe_id: UUID, request: RecipeAdviceRequest):
        snapshot = await asyncio.to_thread(db.recipe_snapshot)
        recipe = next((item for item in snapshot.personal_recipes if item.id == recipe_id), None)
        if recipe is None:
            raise HTTPException(404, "Sincronizează rețeta proprie înainte să ceri sfaturi.")
        data = advice_input(recipe, request.goal, snapshot)
        try:
            return await with_cloud_backup(app.state.cloud_ai, lambda cloud: get_advice(cloud, recipe, data))
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    @app.post("/v1/recipes/options", response_model=DeviceOptions)
    def recipe_options(request: RecipeRequest):
        try:
            snapshot = db.recipe_snapshot()
            context, _ = selection_context(request, snapshot.pantry, snapshot.personal_recipes)
            return context
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc

    @app.post("/v1/recipes/choose", response_model=Recipe)
    def choose_recipe(request: DeviceChoice, epoch: str = Depends(write_epoch)):
        try:
            snapshot = db.recipe_snapshot()
            context, choices = selection_context(request.request, snapshot.pantry, snapshot.personal_recipes)
        except AiError as exc:
            raise HTTPException(exc.status_code, exc.detail) from exc
        if context.fingerprint != request.fingerprint:
            raise HTTPException(409, "Inventarul s-a schimbat. Reia recomandarea pentru datele actuale.")
        if request.recipe_id not in choices:
            raise HTTPException(422, "Alegerea nu se află în lista de rețete permise.")
        key = hashlib.sha256(f"device:qwen3-0.6b|{context.fingerprint}".encode()).hexdigest()
        draft = choices[request.recipe_id]
        recipe = Recipe(**draft.model_dump(), id=request.id, model="Pe dispozitiv · Qwen3 0.6B CPU",
                        expiring_ingredients=recipe_expiry(draft, snapshot.pantry),
                        generation_ms=request.generation_ms, selection_cached=request.selection_cached, request_key=key,
                        generated_at=datetime.now(timezone.utc).isoformat())
        return db.save_recommendation(recipe, epoch=epoch)

    def phone_model_file():
        return Path(os.environ.get("CAMARA_PHONE_MODEL_FILE", "/models/qwen3-0.6b.litertlm"))

    @app.get("/v1/ai/device-model", response_model=DeviceModelInfo)
    def model_info():
        path = phone_model_file()
        return DeviceModelInfo(available=path.is_file() and path.stat().st_size == PHONE_MODEL_BYTES,
                               name="Qwen3 0.6B · CPU int4", bytes=PHONE_MODEL_BYTES, sha256=PHONE_MODEL_SHA, path="/v1/ai/device-model/file")

    @app.get("/v1/ai/device-model/file")
    def download_model():
        info = model_info()
        if not info.available:
            raise HTTPException(404, "Modelul pentru dispozitiv nu este încă disponibil pe server.")
        return FileResponse(phone_model_file(), media_type="application/octet-stream", filename="qwen3-0.6b.litertlm")

    @app.post("/v1/recipes/{recipe_id}/shopping", response_model=list[ShoppingItem])
    def recipe_to_shopping(recipe_id: UUID, epoch: str = Depends(write_epoch)):
        added = db.add_missing(recipe_id, epoch=epoch)
        if added is None:
            raise HTTPException(404, "Rețetă inexistentă.")
        return added

    return app
