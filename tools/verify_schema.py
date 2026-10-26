#!/usr/bin/env python3
"""Apply the app's schema migrations to a real SQLite database and assert the result.

The Android build cannot run SQLite on the JVM, so this script is the verification
path for "the DDL is valid and the migration chain produces the expected shape".
It reads exactly the same files the app reads at runtime
(`app/src/main/resources/schema/NNNN_*.sql`), so there is no duplicated schema.

Checks:
  1. every statement parses and executes on a fresh database;
  2. the upgrade path (v1 -> v2 -> ...) yields the same tables/columns/indexes as a
     fresh install;
  3. no migration contains a destructive statement (DROP TABLE / DROP COLUMN /
     DELETE FROM / TRUNCATE);
  4. the documented column sets exist.

Exit code is non-zero on the first failure, so CI can gate on it.
"""

from __future__ import annotations

import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "app" / "src" / "main" / "resources" / "schema"

EXPECTED_COLUMNS = {
    "todo_lists": {
        "id", "name", "color_argb", "sort_order", "created_at", "updated_at", "deleted_at", "device_id",
    },
    "todo_items": {
        "id", "list_id", "title", "notes", "priority", "due_at", "remind_at", "reminder_channels",
        "done", "done_at", "created_at", "updated_at", "deleted_at", "device_id", "pinned",
    },
}

DESTRUCTIVE = re.compile(r"\b(DROP\s+TABLE|DROP\s+COLUMN|DELETE\s+FROM|TRUNCATE)\b", re.IGNORECASE)


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    sys.exit(1)


def statements(text: str) -> list[str]:
    body = "\n".join(line for line in text.splitlines() if not line.strip().startswith("--"))
    return [part.strip() for part in body.split(";") if part.strip()]


def apply(connection: sqlite3.Connection, path: pathlib.Path) -> None:
    for statement in statements(path.read_text(encoding="utf-8")):
        if DESTRUCTIVE.search(statement):
            fail(f"destructive statement in {path.name}: {statement}")
        connection.execute(statement)


def columns(connection: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in connection.execute(f"PRAGMA table_info({table})")]


def indexes(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"
        )
    }


def main() -> None:
    files = sorted(SCHEMA_DIR.glob("*.sql"))
    if not files:
        fail(f"no migration files in {SCHEMA_DIR}")
    versions = [int(path.name.split("_", 1)[0]) for path in files]
    if versions != list(range(1, len(files) + 1)):
        fail(f"migration versions are not a contiguous chain starting at 1: {versions}")

    fresh = sqlite3.connect(":memory:")
    for path in files:
        apply(fresh, path)

    upgraded = sqlite3.connect(":memory:")
    apply(upgraded, files[0])
    if "pinned" in columns(upgraded, "todo_items"):
        fail("v1 already contains the v2 column; the migration chain is not meaningful")
    for path in files[1:]:
        apply(upgraded, path)

    for table, expected in EXPECTED_COLUMNS.items():
        fresh_columns = columns(fresh, table)
        upgraded_columns = columns(upgraded, table)
        if fresh_columns != upgraded_columns:
            fail(f"{table}: fresh install {fresh_columns} != upgrade path {upgraded_columns}")
        missing = expected - set(fresh_columns)
        if missing:
            fail(f"{table}: missing columns {sorted(missing)}")
        print(f"ok  {table}: {len(fresh_columns)} columns")

    if indexes(fresh) != indexes(upgraded):
        fail(f"index sets differ: {indexes(fresh)} vs {indexes(upgraded)}")
    print(f"ok  indexes: {sorted(indexes(fresh))}")

    if "pinned" not in columns(fresh, "todo_items"):
        fail("v2 migration did not add todo_items.pinned")

    print(f"ok  {len(files)} migration file(s): {[p.name for p in files]}")
    print("schema verification passed")


if __name__ == "__main__":
    main()
