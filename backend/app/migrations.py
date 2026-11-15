"""Transactional upgrades. Never stamp an unexecuted/future schema as current."""
import time

SCHEMA_VERSION = 8


def migrate(db, record_tables):
    version = db.execute("PRAGMA user_version").fetchone()[0]
    if version > SCHEMA_VERSION:
        raise RuntimeError("Baza de date provine dintr-o versiune mai nouă a aplicației.")
    db.execute("CREATE TABLE IF NOT EXISTS migrations (version INTEGER PRIMARY KEY, applied_at REAL NOT NULL)")
    if version < 7:
        db.execute("INSERT OR IGNORE INTO metadata (key,value) VALUES ('snapshot_revision','0')")
        for table in record_tables:
            for event in ("INSERT", "UPDATE", "DELETE"):
                condition = " WHEN OLD.data != NEW.data" if event == "UPDATE" else ""
                db.execute(f"""CREATE TRIGGER IF NOT EXISTS revision_{table}_{event.lower()}
                    AFTER {event} ON {table}{condition} BEGIN
                    UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='snapshot_revision'; END""")
        for table in ("mutations", "pantry_batches", "recommendations"):
            columns = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
            if "created" not in columns:
                db.execute(f"ALTER TABLE {table} ADD COLUMN created REAL NOT NULL DEFAULT 0")
            db.execute(f"CREATE INDEX IF NOT EXISTS {table}_created ON {table}(created)")
        db.execute("CREATE INDEX IF NOT EXISTS ai_cache_created ON ai_cache(created)")
        db.execute("CREATE INDEX IF NOT EXISTS products_barcode ON products(json_extract(data,'$.barcode'))")
        db.execute("CREATE INDEX IF NOT EXISTS receipts_hash ON receipts(json_extract(data,'$.image_sha256'))")
        db.execute("CREATE INDEX IF NOT EXISTS events_batch ON events(json_extract(data,'$.batch_id'))")
        db.execute("INSERT OR REPLACE INTO migrations (version,applied_at) VALUES (7,?)", (time.time(),))
        db.execute("PRAGMA user_version=7")
    if version < 8:
        db.execute("CREATE INDEX IF NOT EXISTS plans_prepared ON plans(json_extract(data,'$.prepared_batch_id'))")
        for table in record_tables:
            db.execute("INSERT OR IGNORE INTO metadata (key,value) VALUES (?, '0')", (f"rev:{table}",))
            for event in ("INSERT", "UPDATE", "DELETE"):
                condition = " WHEN OLD.data != NEW.data" if event == "UPDATE" else ""
                db.execute(f"""CREATE TRIGGER IF NOT EXISTS collection_{table}_{event.lower()}
                    AFTER {event} ON {table}{condition} BEGIN
                    UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='rev:{table}'; END""")
        db.execute("INSERT OR REPLACE INTO migrations (version,applied_at) VALUES (8,?)", (time.time(),))
        db.execute("PRAGMA user_version=8")
