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


class BatchConflict(Exception):
    pass


SCHEMA_VERSION = 6
RECORD_MODELS = {"pantry": PantryItem, "shopping": ShoppingItem, "recipes": Recipe, "plans": MealPlan,
                 "expenses": Expense, "events": FoodEvent, "budgets": Budget, "products": Product,
                 "personal_recipes": PersonalRecipe, "receipts": ReceiptRecord, "prepared": PreparedBatch,
                 "purchases": Purchase, "stock_minimums": StockMinimum}


class Database:
    """Small, shared household database. Every request uses its own connection."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            if db.execute("PRAGMA user_version").fetchone()[0] > SCHEMA_VERSION:
                raise RuntimeError("Baza de date provine dintr-o versiune mai nouă a aplicației.")
            db.execute("PRAGMA journal_mode=WAL")
            for table in RECORD_MODELS:
                db.execute(f"CREATE TABLE IF NOT EXISTS {table} (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS pantry_batches (id TEXT PRIMARY KEY, digest TEXT NOT NULL, count INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS ai_cache (key TEXT PRIMARY KEY, choice TEXT NOT NULL, created REAL NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS recommendations (key TEXT PRIMARY KEY, recipe_id TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS mutations (id TEXT PRIMARY KEY, digest TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("INSERT OR IGNORE INTO metadata VALUES ('epoch',?)", (str(uuid4()),))
            db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")

    @contextmanager
    def connection(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            try:
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

    def put(self, table: str, item, *, epoch: str | None = None):
        table = self.table_name(table)
        with self.write(epoch) as db:
            db.execute(
                f"INSERT INTO {table} (id, data) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET data=excluded.data",
                (str(item.id), item.model_dump_json()),
            )
        return item

    def delete(self, table: str, item_id: UUID, *, epoch: str | None = None):
        table = self.table_name(table)
        with self.write(epoch) as db:
            return db.execute(f"DELETE FROM {table} WHERE id=?", (str(item_id),)).rowcount > 0

    def records(self, table: str):
        table = self.table_name(table)
        with self.connection() as db:
            return [RECORD_MODELS[table].model_validate_json(row[0]) for row in db.execute(f"SELECT data FROM {table} ORDER BY rowid DESC")]

    def snapshot(self) -> Snapshot:
        with self.connection() as db:
            # Keep data from every table in the same SQLite read transaction.
            db.execute("BEGIN")
            pantry = [PantryItem.model_validate_json(r[0]) for r in db.execute("SELECT data FROM pantry ORDER BY rowid DESC")]
            shopping = [ShoppingItem.model_validate_json(r[0]) for r in db.execute("SELECT data FROM shopping ORDER BY rowid DESC")]
            recipes = [Recipe.model_validate_json(r[0]) for r in db.execute("SELECT data FROM recipes ORDER BY rowid DESC LIMIT 30")]
            models = {key: model for key, model in RECORD_MODELS.items() if key not in {"pantry", "shopping", "recipes"}}
            extra = {table: [model.model_validate_json(row[0]) for row in db.execute(f"SELECT data FROM {table} ORDER BY rowid DESC")] for table, model in models.items()}
            metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('epoch','restored_at')"))
        return Snapshot(pantry=pantry, shopping=shopping, recipes=recipes, aliases=ALIASES,
                        epoch=metadata["epoch"], restored_at=metadata.get("restored_at"), **extra)

    def recipe_snapshot(self) -> Snapshot:
        # Recipe selection needs neither the activity history nor shopping and planning records.
        with self.readonly_connection() as db:
            db.execute("BEGIN")
            pantry = [PantryItem.model_validate_json(row[0]) for row in db.execute("SELECT data FROM pantry ORDER BY rowid DESC")]
            personal = [RECORD_MODELS["personal_recipes"].model_validate_json(row[0])
                        for row in db.execute("SELECT data FROM personal_recipes ORDER BY rowid DESC")]
            epoch = db.execute("SELECT value FROM metadata WHERE key='epoch'").fetchone()[0]
        return Snapshot(pantry=pantry, shopping=[], recipes=[], personal_recipes=personal, epoch=epoch)

    @staticmethod
    def require_epoch(db, epoch: str | None):
        metadata = dict(db.execute("SELECT key,value FROM metadata WHERE key IN ('epoch','restored_at')"))
        current = metadata["epoch"]
        if epoch != current and (epoch or metadata.get("restored_at")):
            raise BatchConflict("Serverul a fost restaurat. Actualizează aplicația înainte de a salva.")
        return current

    def checked_epoch(self, epoch: str | None):
        with self.connection() as db:
            return self.require_epoch(db, epoch)

    def mutate(self, mutation_id: UUID, request: Mutation) -> MutationResult:
        """Compare-and-swap the whole user action in one transaction; retries are durable."""
        models = RECORD_MODELS
        payload = request.model_dump(mode="json")
        payload.pop("epoch") # Epoch is a precondition; preserve retry digests from earlier clients.
        if not payload["guards"]:
            payload.pop("guards")
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
            previous = db.execute("SELECT digest FROM mutations WHERE id=?", (str(mutation_id),)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise BatchConflict("Această acțiune a fost deja confirmată cu alte date.")
                return MutationResult(replayed=True)
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
            if modified & {"events", "prepared", "plans"}:
                self.validate_prepared(db)
            if "stock_minimums" in modified:
                self.validate_stock_minimums(db)
            db.execute("INSERT INTO mutations VALUES (?,?)", (str(mutation_id), digest))
        return MutationResult()

    @staticmethod
    def validate_products(db):
        products = (Product.model_validate_json(row[0]) for row in db.execute("SELECT data FROM products"))
        codes = [product.barcode for product in products if product.barcode]
        if len(codes) != len(set(codes)):
            raise BatchConflict("Acest cod de bare este deja asociat altui produs din catalog.")

    @staticmethod
    def validate_receipts(db):
        hashes = [ReceiptRecord.model_validate_json(row[0]).image_sha256 for row in db.execute("SELECT data FROM receipts")]
        if len(set(hashes)) != len(hashes):
            raise BatchConflict("Această fotografie de bon a fost deja importată.")

    @staticmethod
    def validate_stock_minimums(db):
        names = [canonical(StockMinimum.model_validate_json(row[0]).name) for row in db.execute("SELECT data FROM stock_minimums")]
        if len(names) > 200:
            raise BatchConflict("Poți păstra cel mult 200 de praguri de stoc.")
        if len(set(names)) != len(names):
            raise BatchConflict("Acest aliment are deja un prag de stoc minim.")

    @staticmethod
    def validate_prepared(db):
        batches = {str(row.id): row for row in (PreparedBatch.model_validate_json(r[0]) for r in db.execute("SELECT data FROM prepared"))}
        created, consumed = {}, {}
        for row in db.execute("SELECT data FROM events WHERE json_extract(data, '$.batch_id') IS NOT NULL"):
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
        for row in db.execute("SELECT data FROM plans"):
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
                db.execute("INSERT INTO shopping VALUES (?, ?)", (str(item.id), item.model_dump_json()))
                names.add(key)
                added.append(item)
            return added

    def import_batch(self, batch_id: UUID, batch: PantryBatch, *, epoch: str | None = None) -> BatchResult:
        digest = hashlib.sha256(json.dumps(batch.model_dump(mode="json"), sort_keys=True).encode()).hexdigest()
        with self.write(epoch) as db:
            previous = db.execute("SELECT digest, count FROM pantry_batches WHERE id=?", (str(batch_id),)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise BatchConflict("Această importare a fost deja confirmată cu alte date.")
                return BatchResult(count=previous[1], replayed=True)
            try:
                for item in batch.items:
                    db.execute("INSERT INTO pantry VALUES (?, ?)", (str(item.id), item.model_dump_json()))
            except sqlite3.IntegrityError as exc:
                raise BatchConflict("Un identificator este deja folosit. Reia previzualizarea.") from exc
            db.execute("INSERT INTO pantry_batches VALUES (?, ?, ?)", (str(batch_id), digest, len(batch.items)))
        return BatchResult(count=len(batch.items))

    def cached_choice(self, key: str) -> str | None:
        with self.connection() as db:
            row = db.execute("SELECT choice FROM ai_cache WHERE key=? AND created>?", (key, time.time() - 86400)).fetchone()
            return row[0] if row else None

    def cache_choice(self, key: str, choice: str):
        with self.connection() as db:
            db.execute("INSERT INTO ai_cache VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET choice=excluded.choice, created=excluded.created", (key, choice, time.time()))
            db.execute("DELETE FROM ai_cache WHERE created<?", (time.time() - 86400,))
            db.execute("DELETE FROM ai_cache WHERE key NOT IN (SELECT key FROM ai_cache ORDER BY created DESC LIMIT 512)")

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
            db.execute("INSERT INTO recipes VALUES (?, ?)", (str(recipe.id), recipe.model_dump_json()))
            db.execute("INSERT INTO recommendations VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET recipe_id=excluded.recipe_id", (recipe.request_key, str(recipe.id)))
            return recipe
