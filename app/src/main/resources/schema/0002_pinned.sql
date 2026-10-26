-- Schema v2 — "pin to top".
-- Explicit, additive migration: no table is dropped, no data is rewritten, and
-- existing rows get the documented default (not pinned).
ALTER TABLE todo_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
