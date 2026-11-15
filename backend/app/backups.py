"""Consistent SQLite copies and transactional restore with a new synchronization epoch."""
import hashlib
import json
import os
import sqlite3
import threading
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from pydantic import Field
from .database import Database, BatchConflict, RECORD_MODELS, SCHEMA_VERSION
from .models import Model


class BackupSettings(Model):
    enabled: bool = True
    hour: int = Field(default=3, ge=0, le=23)
    minute: int = Field(default=0, ge=0, le=59)
    keep_daily: int = Field(default=14, ge=3, le=90)


class BackupInfo(Model):
    id: UUID
    created_at: str
    kind: str
    size_bytes: int
    sha256: str
    counts: dict[str, int]


class RestoreRequest(Model):
    request_id: UUID
    expected_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    backup_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    confirmation: str


class BackupManager:
    def __init__(self, database: Database, directory: Path):
        self.db, self.directory = database, directory
        self.lock = threading.RLock()
        self.last_error = None
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)

    def atomic_json(self, name, data):
        target = self.directory / name
        temporary = self.directory / (name + ".tmp")
        try:
            with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w", encoding="utf-8") as output:
                json.dump(data, output, ensure_ascii=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)

    def settings(self):
        path = self.directory / "settings.json"
        return BackupSettings.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else BackupSettings()

    def configure(self, settings: BackupSettings):
        with self.lock:
            self.atomic_json("settings.json", settings.model_dump())
        return settings

    def get(self, backup_id: UUID):
        name = str(UUID(str(backup_id)))
        path = self.directory / (name + ".json")
        if not path.is_file():
            raise FileNotFoundError("Copia nu mai este disponibilă.")
        info = BackupInfo.model_validate_json(path.read_text(encoding="utf-8"))
        if str(info.id) != name or not (self.directory / (name + ".db")).is_file():
            raise ValueError("Copia este incompletă.")
        return info

    def list(self):
        with self.lock:
            values = []
            for path in self.directory.glob("*.json"):
                try:
                    values.append(self.get(UUID(path.stem)))
                except (ValueError, FileNotFoundError):
                    continue
            return sorted(values, key=lambda item: item.created_at, reverse=True)

    @staticmethod
    def counts(connection):
        return {table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in RECORD_MODELS}

    @staticmethod
    def digest(path: Path):
        with path.open("rb") as source:
            return hashlib.file_digest(source, "sha256").hexdigest()

    def create(self, kind="manual"):
        with self.lock, self.db.lock:
            backup_id = uuid4()
            temporary = self.directory / f"{backup_id}.partial"
            target = self.directory / f"{backup_id}.db"
            try:
                with self.db.connection() as source, closing(sqlite3.connect(temporary)) as destination:
                    source.backup(destination)
                    if destination.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                        raise ValueError("Copia nu a trecut verificarea SQLite.")
                    counts = self.counts(destination)
                temporary.chmod(0o600)
                digest = self.digest(temporary)
                os.replace(temporary, target)
                info = BackupInfo(id=backup_id, created_at=datetime.now(timezone.utc).isoformat(), kind=kind,
                                  size_bytes=target.stat().st_size, sha256=digest, counts=counts)
                try:
                    self.atomic_json(f"{backup_id}.json", info.model_dump(mode="json"))
                except OSError:
                    target.unlink(missing_ok=True)
                    raise
                self.last_error = None
                self.prune()
                return info
            finally:
                temporary.unlink(missing_ok=True)

    def prune(self):
        # Only app-created UUID metadata and its matching database are managed here.
        limits = {"auto": self.settings().keep_daily, "manual": 30, "pre_restore": 30}
        backups = self.list()
        for kind, limit in limits.items():
            for info in [entry for entry in backups if entry.kind == kind][limit:]:
                (self.directory / f"{info.id}.json").unlink(missing_ok=True)
                (self.directory / f"{info.id}.db").unlink(missing_ok=True)

    def maybe_auto(self, now=None):
        with self.lock:
            now = now or datetime.now(ZoneInfo("Europe/Bucharest"))
            settings = self.settings()
            done_today = any(info.kind == "auto" and datetime.fromisoformat(info.created_at).astimezone(now.tzinfo).date() == now.date() for info in self.list())
            if settings.enabled and (now.hour, now.minute) >= (settings.hour, settings.minute) and not done_today:
                return self.create("auto")
        return None

    def fingerprint(self):
        with self.db.connection() as connection:
            records = {table: list(connection.execute(f"SELECT id,data FROM {table} ORDER BY id")) for table in RECORD_MODELS}
            records["epoch"] = connection.execute("SELECT value FROM metadata WHERE key='epoch'").fetchone()[0]
        return hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()

    def preview(self, backup_id):
        with self.lock, self.db.lock:
            info = self.get(backup_id)
            with self.db.connection() as connection:
                current = self.counts(connection)
            return {"backup": info.model_dump(mode="json"), "current_counts": current, "fingerprint": self.fingerprint()}

    def restore(self, backup_id, request: RestoreRequest):
        with self.lock, self.db.lock:
            key = f"restore:{request.request_id}"
            with self.db.connection() as connection:
                stored = connection.execute("SELECT value FROM metadata WHERE key=?", (key,)).fetchone()
            result_path = self.directory / f"restore-{request.request_id}.json"  # Results from previous releases.
            previous = json.loads(stored[0]) if stored else (
                json.loads(result_path.read_text(encoding="utf-8")) if result_path.is_file() else None
            )
            request_data = request.model_dump(mode="json")
            if previous is not None:
                if previous["backup_id"] != str(backup_id) or previous["request"] != request_data:
                    raise BatchConflict("Identificatorul restaurării este deja folosit.")
                return previous["result"]
            if request.confirmation != "RESTAUREAZĂ":
                raise ValueError("Confirmă restaurarea în aplicație.")
            info = self.get(backup_id)
            if info.sha256 != request.backup_sha256 or self.fingerprint() != request.expected_fingerprint:
                raise BatchConflict("Datele sau copia s-au schimbat. Verifică din nou previzualizarea restaurării.")
            path = self.directory / f"{info.id}.db"
            if self.digest(path) != info.sha256:
                raise ValueError("Copia nu a trecut verificarea SHA-256.")
            rows = {}
            with closing(sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)) as source:
                if source.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or source.execute("PRAGMA user_version").fetchone()[0] > SCHEMA_VERSION:
                    raise ValueError("Copia este deteriorată sau provine dintr-o versiune mai nouă.")
                available = {row[0] for row in source.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if not {"pantry", "shopping", "recipes"}.issubset(available):
                    raise ValueError("Copia nu este o bază Cămara.")
                for table, model in RECORD_MODELS.items():
                    rows[table] = []
                    if table in available:
                        for item_id, raw in source.execute(f"SELECT id,data FROM {table} ORDER BY rowid"):
                            item = model.model_validate_json(raw)
                            if str(item.id) != item_id:
                                raise ValueError("Identificatori nevalizi în copie.")
                            rows[table].append((item_id, item.model_dump_json()))
            safety = self.create("pre_restore")
            epoch = str(uuid4())
            restored_at = datetime.now(timezone.utc).isoformat()
            result = {"epoch": epoch, "restored_at": restored_at, "safety_backup_id": str(safety.id)}
            receipt = {"backup_id": str(backup_id), "request": request_data, "result": result}
            with self.db.connection() as destination:
                destination.execute("BEGIN IMMEDIATE")
                for table, values in rows.items():
                    destination.execute(f"DELETE FROM {table}")
                    destination.executemany(f"INSERT INTO {table} (id,data) VALUES (?,?)", values)
                for table in ("mutations", "pantry_batches", "ai_cache", "recommendations"):
                    destination.execute(f"DELETE FROM {table}")
                self.db.validate_products(destination)
                self.db.validate_receipts(destination)
                self.db.validate_prepared(destination)
                self.db.validate_stock_minimums(destination)
                destination.execute("UPDATE metadata SET value=? WHERE key='epoch'", (epoch,))
                destination.execute("INSERT INTO metadata VALUES ('restored_at',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (restored_at,))
                destination.execute("INSERT INTO metadata VALUES (?,?)", (key, json.dumps(receipt)))
            return result
