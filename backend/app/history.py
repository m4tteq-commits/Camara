"""Keyset pagination for the history screens. Restoring the database invalidates cursors."""
import base64
import re
from typing import Literal

from pydantic import Field

from .database import BatchConflict, RECORD_MODELS
from .models import Model

HistoryKind = Literal["events", "expenses", "purchases", "receipts"]
HISTORY_TABLES = {"events", "expenses", "purchases", "receipts"}


class Cursor(Model):
    table: HistoryKind
    epoch: str = Field(min_length=1, max_length=64)
    before: int = Field(ge=1, le=2**63 - 1)

    def encode(self):
        return base64.urlsafe_b64encode(self.model_dump_json().encode()).decode().rstrip("=")

    @classmethod
    def decode(cls, value: str):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,256}", value):
            raise ValueError("Cursorul istoricului nu este valid.")
        try:
            return cls.model_validate_json(base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True))
        except ValueError as exc:
            raise ValueError("Cursorul istoricului nu este valid.") from exc


def read_history(db, table: HistoryKind, limit: int, cursor_value: str | None):
    if table not in HISTORY_TABLES:
        raise ValueError("Istoricul cerut nu este disponibil.")
    cursor = Cursor.decode(cursor_value) if cursor_value is not None else None
    if cursor is not None and cursor.table != table:
        raise ValueError("Cursorul aparține altui tip de istoric.")
    with db.readonly_connection() as connection:
        connection.execute("BEGIN")
        epoch = connection.execute("SELECT value FROM metadata WHERE key='epoch'").fetchone()[0]
        if cursor is not None:
            if cursor.epoch != epoch:
                raise BatchConflict("Serverul a fost restaurat. Reîncarcă istoricul de la prima pagină.")
            rows = connection.execute(f"SELECT rowid,data FROM {table} WHERE rowid < ? ORDER BY rowid DESC LIMIT ?",
                                      (cursor.before, limit + 1)).fetchall()
        else:
            rows = connection.execute(f"SELECT rowid,data FROM {table} ORDER BY rowid DESC LIMIT ?",
                                      (limit + 1,)).fetchall()
    page = rows[:limit]
    next_cursor = Cursor(table=table, epoch=epoch, before=page[-1][0]).encode() if len(rows) > limit else None
    model = RECORD_MODELS[table]
    return {"items": [model.model_validate_json(raw, extra="ignore").model_dump(mode="json") for _, raw in page],
            "next_cursor": next_cursor}
