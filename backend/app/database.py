import sqlite3
import hashlib
import json
import time
import threading
from contextlib import closing, contextmanager
from pathlib import Path
from uuid import UUID, uuid4

from .models import (BatchResult, PantryBatch, PantryItem, Recipe, ShoppingItem, Snapshot,
                     MealPlan, Expense, FoodEvent, Budget, Mutation, MutationResult, Product, PersonalRecipe,
                     ReceiptRecord, PreparedBatch, Purchase, StockMinimum)
from .catalog import ALIASES, canonical
from .migrations import SCHEMA_VERSION, migrate


class BatchConflict(Exception):
    pass


class PreconditionRequired(Exception):
    pass


REPLAY_SECONDS = 90 * 24 * 3600
CLOCK_LEEWAY = 300


def check_replay_window(identifier: UUID, now=None):
    if identifier.version != 7:
        return
    now = time.time() if now is None else now
    issued = (identifier.int >> 80) / 1000
    if issued < now - REPLAY_SECONDS:
        raise BatchConflict("Acțiunea a stat prea mult offline. Verifică datele și refă operația.")
    if issued > now + CLOCK_LEEWAY:
        raise BatchConflict("Ora dispozitivului este prea departe de ora serverului. Corecteaz-o și refă operația.")


RECORD_MODELS = {"pantry": PantryItem, "shopping": ShoppingItem, "recipes": Recipe, "plans": MealPlan,
                 "expenses": Expense, "events": FoodEvent, "budgets": Budget, "products": Product,
                 "personal_recipes": PersonalRecipe, "receipts": ReceiptRecord, "prepared": PreparedBatch,
                 "purchases": Purchase, "stock_minimums": StockMinimum}


class Database:
    """Small, shared household database. Every request uses its own connection."""

    def __init__(self, path: Path):
        if str(path).startswith(("\\\\", "//")):
            raise ValueError("SQLite trebuie păstrat pe un disc local, nu pe un director de rețea.")
        self.path = path
        self.lock = threading.RLock()
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            if db.execute("PRAGMA user_version").fetchone()[0] > SCHEMA_VERSION:
                raise RuntimeError("Baza de date provine dintr-o versiune mai nouă a aplicației.")
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("BEGIN IMMEDIATE")
            for table in RECORD_MODELS:
                db.execute(f"CREATE TABLE IF NOT EXISTS {table} (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS pantry_batches (id TEXT PRIMARY KEY, digest TEXT NOT NULL, count INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS ai_cache (key TEXT PRIMARY KEY, choice TEXT NOT NULL, created REAL NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS recommendations (key TEXT PRIMARY KEY, recipe_id TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS mutations (id TEXT PRIMARY KEY, digest TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("INSERT OR IGNORE INTO metadata (key,value) VALUES ('epoch',?)", (str(uuid4()),))
            migrate(db, RECORD_MODELS)

    @contextmanager
    def connection(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            try:
                db.execute("PRAGMA busy_timeout=10000")
                db.execute("PRAGMA cache_size=-4096")
                db.execute("PRAGMA mmap_size=16777216")
                db.execute("PRAGMA wal_autocheckpoint=512")
                # Retain FULL durability for acknowledged writes across power loss.
                with db:
                    yield db
            finally:
                db.close()

    def readonly_connection(self):
        return closing(sqlite3.connect(f"{self.path.resolve().as_uri()}?mode=ro", uri=True, timeout=2))

    @staticmethod
    def table_name(table: str):
        if table not in RECORD_MODELS:
            raise ValueError("Unknown table")
        return table

    @contextmanager
    def write(self, epoch: str | None = None):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            if epoch is not None:
                self.require_epoch(db, epoch)
            yield db

    @staticmethod
    def record_revision(raw: str | None):
        return hashlib.sha256(raw.encode()).hexdigest() if raw is not None else "new"

    def require_revision(self, db, table, item_id, revision):
        row = db.execute(f"SELECT data FROM {table} WHERE id=?", (str(item_id),)).fetchone()
        if row and revision is None:
            raise PreconditionRequired("Actualizează datele și retrimite revizia înregistrării înainte de modificare.")
        if revision is not None and revision != self.record_revision(row[0] if row else None):
            raise BatchConflict("Datele au fost schimbate pe alt dispozitiv. Actualizează înainte de salvare.")

    def put(self, table: str, item, *, epoch: str | None = None, revision=None, conditional=False):
        table = self.table_name(table)
        with self.write(epoch) as db:
            if conditional:
                self.require_revision(db, table, item.id, revision)
            db.execute(
                f"INSERT INTO {table} (id, data) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET data=excluded.data",
                (str(item.id), item.model_dump_json()),
            )
        return item

    def delete(self, table: str, item_id: UUID, *, epoch: str | None = None, revision=None, conditional=False):
        table = self.table_name(table)
        with self.write(epoch) as db:
            if conditional:
                self.require_revision(db, table, item_id, revision)
            return db.execute(f"DELETE FROM {table} WHERE id=?", (str(item_id),)).rowcount > 0

    def records(self, table: str):
        table = self.table_name(table)
        with self.connection() as db:
            return [RECORD_MODELS[table].model_validate_json(row[0]) for row in db.execute(f"SELECT data FROM {table} ORDER BY rowid DESC")]

    def snapshot_response(self, if_none_match: str | None = None, cached_tag: str | None = None):
        with self.readonly_connection() as db:
            db.execute("BEGIN")
            metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('epoch','restored_at','snapshot_revision') OR key LIKE 'rev:%'"))
            # Bump representation version if serialization or static aliases change.
            etag = f'"snapshot-v1-{SCHEMA_VERSION}-{metadata["epoch"]}-{metadata["snapshot_revision"]}"'
            if self.etag_matches(if_none_match, etag) or cached_tag == etag:
                return etag, None
            rows = {table: db.execute(f"SELECT data FROM {table} ORDER BY rowid DESC" +
                                      (" LIMIT 30" if table == "recipes" else "")).fetchall()
                    for table in RECORD_MODELS}
        # Validation/serialization is outside the SQLite read transaction and write mutex.
        values = {table: [RECORD_MODELS[table].model_validate_json(raw, extra="ignore") for (raw,) in items]
                  for table, items in rows.items()}
        revisions = {key[4:]: value for key, value in metadata.items() if key.startswith("rev:")}
        return etag, Snapshot(aliases=ALIASES, epoch=metadata["epoch"],
                              restored_at=metadata.get("restored_at"), collection_revisions=revisions, **values)

    def snapshot(self) -> Snapshot:
        return self.snapshot_response()[1]

    @staticmethod
    def etag_matches(header, etag):
        tags = {tag.strip().removeprefix("W/") for tag in (header or "").split(",")}
        return etag in tags or "*" in tags

    def recipe_snapshot(self) -> Snapshot:
        return self.recipe_snapshot_if_changed()[1]

    def recipe_snapshot_if_changed(self, previous_version=None):
        # Recipe selection needs neither the activity history nor shopping and planning records.
        with self.readonly_connection() as db:
            db.execute("BEGIN")
            metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('epoch','restored_at','rev:pantry','rev:personal_recipes')"))
            version = (metadata["epoch"], metadata["rev:pantry"], metadata["rev:personal_recipes"])
            if version == previous_version:
                return version, None
            pantry = [PantryItem.model_validate_json(row[0], extra="ignore") for row in db.execute("SELECT data FROM pantry ORDER BY rowid DESC")]
            personal = [RECORD_MODELS["personal_recipes"].model_validate_json(row[0], extra="ignore")
                        for row in db.execute("SELECT data FROM personal_recipes ORDER BY rowid DESC")]
        return version, Snapshot(pantry=pantry, shopping=[], recipes=[], personal_recipes=personal,
                                 epoch=version[0], restored_at=metadata.get("restored_at"))

    @staticmethod
    def ensure_epoch(epoch: str | None, current: str, restored_at: str | None):
        if epoch != current and (epoch or restored_at):
            raise BatchConflict("Serverul a fost restaurat. Actualizează aplicația înainte de a salva.")
        return current

    @staticmethod
    def require_epoch(db, epoch: str | None):
        metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('epoch','restored_at')"))
        return Database.ensure_epoch(epoch, metadata["epoch"], metadata.get("restored_at"))

    def mutate(self, mutation_id: UUID, request: Mutation) -> MutationResult:
        """Compare-and-swap the whole user action in one transaction; retries are durable."""
        models = RECORD_MODELS
        payload = request.model_dump(mode="json")
        payload.pop("epoch") # Epoch is a precondition; preserve retry digests from earlier clients.
        if not payload["guards"]:
            payload.pop("guards")
        if not payload["guard_revisions"]:
            payload.pop("guard_revisions")
        if set(request.guards) & set(request.guard_revisions):
            raise ValueError("Fiecare colecție poate avea o singură condiție.")
        if request.guard_revisions and not request.epoch:
            raise ValueError("Actualizează datele înainte de a salva.")
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
        seen, changes = set(), []
        for change in request.changes:
            identity = (change.kind, str(change.id))
            if identity in seen or (change.before is None and change.after is None):
                raise ValueError("O înregistrare poate fi modificată o singură dată într-o acțiune.")
            seen.add(identity)
            def normalize(value):
                if value is None:
                    return None
                item = models[change.kind].model_validate(value)
                if item.id != change.id:
                    raise ValueError("Identificatorii nu corespund.")
                return item.model_dump(mode="json")
            changes.append((change.kind, str(change.id), normalize(change.before), normalize(change.after)))
        with self.write(request.epoch) as db:
            check_replay_window(mutation_id)
            previous = db.execute("SELECT digest FROM mutations WHERE id=?", (str(mutation_id),)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise BatchConflict("Această acțiune a fost deja confirmată cu alte date.")
                return MutationResult(replayed=True)
            for table, expected in request.guard_revisions.items():
                row = db.execute("SELECT value FROM metadata WHERE key=?", (f"rev:{table}",)).fetchone()
                if row is None or row[0] != expected:
                    raise BatchConflict("Datele folosite pentru calcul s-au schimbat. Recalculează propunerea.")
            for table, expected in request.guards.items():
                supplied = [models[table].model_validate(value).model_dump(mode="json") for value in expected]
                supplied_by_id = {value["id"]: value for value in supplied}
                if len(supplied_by_id) != len(supplied):
                    raise ValueError("Identificatori repetați în verificarea stocului.")
                current = [models[table].model_validate_json(row[0]).model_dump(mode="json") for row in db.execute(f"SELECT data FROM {table}")]
                if {value["id"]: value for value in current} != supplied_by_id:
                    raise BatchConflict("Inventarul, planul sau lista s-au schimbat. Recalculează cumpărăturile folosind datele actualizate.")
            for table, item_id, before, after in changes:
                row = db.execute(f"SELECT data FROM {table} WHERE id=?", (item_id,)).fetchone()
                current = models[table].model_validate_json(row[0]).model_dump(mode="json") if row else None
                if current != before:
                    raise BatchConflict("Datele au fost schimbate pe alt dispozitiv. Alege ce variantă păstrezi în Sincronizare.")
            for table, item_id, before, after in changes:
                if after is None:
                    db.execute(f"DELETE FROM {table} WHERE id=?", (item_id,))
                else:
                    db.execute(f"INSERT INTO {table} (id,data) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", (item_id, json.dumps(after)))
            modified = {table for table, *_ in changes}
            if "products" in modified:
                self.validate_products(db)
            if "receipts" in modified:
                self.validate_receipts(db)
            affected_batches = set()
            for table, item_id, before, after in changes:
                if table == "prepared":
                    affected_batches.add(item_id)
                elif table in {"events", "plans"}:
                    field = "batch_id" if table == "events" else "prepared_batch_id"
                    affected_batches.update(value[field] for value in (before, after) if value and value.get(field))
            if affected_batches:
                self.validate_prepared(db, affected_batches)
            if "stock_minimums" in modified:
                self.validate_stock_minimums(db)
            db.execute("INSERT INTO mutations (id,digest,created) VALUES (?,?,?)", (str(mutation_id), digest, time.time()))
        return MutationResult()

    @staticmethod
    def validate_products(db):
        duplicate = db.execute("""SELECT 1 FROM products WHERE json_extract(data,'$.barcode') > ''
            GROUP BY json_extract(data,'$.barcode') HAVING COUNT(*) > 1 LIMIT 1""").fetchone()
        if duplicate:
            raise BatchConflict("Acest cod de bare este deja asociat altui produs din catalog.")

    @staticmethod
    def validate_receipts(db):
        duplicate = db.execute("""SELECT 1 FROM receipts GROUP BY json_extract(data,'$.image_sha256')
            HAVING COUNT(*) > 1 LIMIT 1""").fetchone()
        if duplicate:
            raise BatchConflict("Această fotografie de bon a fost deja importată.")

    @staticmethod
    def validate_stock_minimums(db):
        names = [canonical(StockMinimum.model_validate_json(row[0]).name) for row in db.execute("SELECT data FROM stock_minimums")]
        if len(names) > 200:
            raise BatchConflict("Poți păstra cel mult 200 de praguri de stoc.")
        if len(set(names)) != len(names):
            raise BatchConflict("Acest aliment are deja un prag de stoc minim.")

    @staticmethod
    def validate_prepared(db, batch_ids=None):
        def related(table, column):
            query = f"SELECT data FROM {table}"
            if batch_ids is None:
                return db.execute(query + f" WHERE {column} IS NOT NULL")
            keys = sorted(batch_ids)
            return db.execute(query + f" WHERE {column} IN ({','.join('?' for _ in keys)})", keys)

        batches = {str(row.id): row for row in (PreparedBatch.model_validate_json(r[0]) for r in related("prepared", "id"))}
        created, consumed = {}, {}
        for row in related("events", "json_extract(data, '$.batch_id')"):
            event = FoodEvent.model_validate_json(row[0])
            if event.undone:
                continue
            key = str(event.batch_id)
            batch = batches.get(key)
            if batch is None:
                raise BatchConflict("Lotul gătit a fost schimbat. Actualizează datele.")
            if event.kind == 'served' and not batch.cooked_on <= event.occurred_on <= batch.expires_on:
                raise BatchConflict("Data consumului trebuie să respecte termenul introdus pentru lot.")
            if event.kind == 'prepared':
                if event.occurred_on != batch.cooked_on or key in created:
                    raise BatchConflict("Data gătirii sau înregistrarea preparării nu corespunde lotului.")
                created[key] = event.portions
            if event.kind in {'served', 'portion_wasted'}:
                consumed[key] = consumed.get(key, 0) + event.portions
        for key, batch in batches.items():
            if created.get(key) != batch.total or batch.remaining != batch.total - consumed.get(key, 0):
                raise BatchConflict("Porțiile nu corespund istoricului. Reia operațiunea pe datele actuale.")
        reserved = {}
        for row in related("plans", "json_extract(data, '$.prepared_batch_id')"):
            plan = MealPlan.model_validate_json(row[0])
            if plan.completed or plan.prepared_batch_id is None:
                continue
            key = str(plan.prepared_batch_id)
            batch = batches.get(key)
            if batch is None or batch.recipe.id != plan.recipe.id or not batch.cooked_on <= plan.on_date <= batch.expires_on:
                raise BatchConflict("Masa trebuie legată de un lot disponibil, în termenul introdus.")
            reserved[key] = reserved.get(key, 0) + plan.servings
            if reserved[key] > batch.remaining:
                raise BatchConflict("Porțiile disponibile sunt deja rezervate în plan.")

    def add_missing(self, recipe_id: UUID, *, epoch: str | None = None) -> list[ShoppingItem] | None:
        """Add missing ingredients atomically; repeated taps do not duplicate them."""
        with self.write(epoch) as db:
            row = db.execute("SELECT data FROM recipes WHERE id=?", (str(recipe_id),)).fetchone()
            if row is None:
                return None
            recipe = Recipe.model_validate_json(row[0])
            existing = [ShoppingItem.model_validate_json(r[0]) for r in db.execute("SELECT data FROM shopping")]
            names = {canonical(item.name) for item in existing if not item.checked}
            added = []
            for ingredient in recipe.missing_ingredients:
                key = canonical(ingredient.name)
                if key in names:
                    continue
                quantity = ingredient.quantity if len(ingredient.quantity) <= 80 else ""
                item = ShoppingItem(id=uuid4(), name=ingredient.name, quantity=quantity)
                db.execute("INSERT INTO shopping (id,data) VALUES (?, ?)", (str(item.id), item.model_dump_json()))
                names.add(key)
                added.append(item)
            return added

    def import_batch(self, batch_id: UUID, batch: PantryBatch, *, epoch: str | None = None) -> BatchResult:
        digest = hashlib.sha256(json.dumps(batch.model_dump(mode="json"), sort_keys=True).encode()).hexdigest()
        with self.write(epoch) as db:
            check_replay_window(batch_id)
            previous = db.execute("SELECT digest, count FROM pantry_batches WHERE id=?", (str(batch_id),)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise BatchConflict("Această importare a fost deja confirmată cu alte date.")
                return BatchResult(count=previous[1], replayed=True)
            try:
                for item in batch.items:
                    db.execute("INSERT INTO pantry (id,data) VALUES (?, ?)", (str(item.id), item.model_dump_json()))
            except sqlite3.IntegrityError as exc:
                raise BatchConflict("Un identificator este deja folosit. Reia previzualizarea.") from exc
            db.execute("INSERT INTO pantry_batches (id,digest,count,created) VALUES (?, ?, ?, ?)", (str(batch_id), digest, len(batch.items), time.time()))
        return BatchResult(count=len(batch.items))

    def cached_choice(self, key: str) -> str | None:
        with self.readonly_connection() as db:
            row = db.execute("SELECT choice FROM ai_cache WHERE key=? AND created>?", (key, time.time() - 86400)).fetchone()
            return row[0] if row else None

    def cache_choice(self, key: str, choice: str):
        with self.connection() as db:
            db.execute("INSERT INTO ai_cache (key,choice,created) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET choice=excluded.choice, created=excluded.created", (key, choice, time.time()))
            db.execute("""DELETE FROM ai_cache WHERE created < COALESCE(
                (SELECT created FROM ai_cache ORDER BY created DESC LIMIT 1 OFFSET 511),0)""")

    def collect_stale(self, now=None):
        now = time.time() if now is None else now
        with self.connection() as db:
            for table in ("mutations", "pantry_batches"):
                # Only UUIDv7 has an enforced age. Keep legacy receipts to preserve idempotence.
                db.execute(f"DELETE FROM {table} WHERE created < ? AND substr(id,15,1)='7'",
                           (now - REPLAY_SECONDS - CLOCK_LEEWAY,))
            db.execute("DELETE FROM ai_cache WHERE created < ?", (now - 86400,))
            db.execute("""DELETE FROM recommendations WHERE NOT EXISTS
                (SELECT 1 FROM recipes WHERE recipes.id=recommendations.recipe_id)""")

    def save_recommendation(self, recipe: Recipe, *, epoch: str | None = None) -> Recipe:
        if not recipe.request_key:
            return self.put("recipes", recipe, epoch=epoch)
        with self.write(epoch) as db:
            row = db.execute("SELECT r.data FROM recommendations k JOIN recipes r ON r.id=k.recipe_id WHERE k.key=?", (recipe.request_key,)).fetchone()
            if row:
                saved = Recipe.model_validate_json(row[0])
                metadata = {"id", "generated_at", "generation_ms", "selection_cached"}
                if saved.model_dump(exclude=metadata) == recipe.model_dump(exclude=metadata):
                    return saved.model_copy(update={"selection_cached": recipe.selection_cached, "generation_ms": recipe.generation_ms})
            if db.execute("SELECT 1 FROM recipes WHERE id=?", (str(recipe.id),)).fetchone():
                raise BatchConflict("Identificatorul recomandării este deja folosit.")
            db.execute("INSERT INTO recipes (id,data) VALUES (?, ?)", (str(recipe.id), recipe.model_dump_json()))
            db.execute("INSERT INTO recommendations (key,recipe_id,created) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET recipe_id=excluded.recipe_id, created=excluded.created", (recipe.request_key, str(recipe.id), time.time()))
            return recipe
