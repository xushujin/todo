-- Schema v1 — initial shape.
-- Applied verbatim to fresh installs and to databases created before v2.
CREATE TABLE IF NOT EXISTS todo_lists (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    color_argb INTEGER NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    device_id TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS todo_items (
    id TEXT NOT NULL PRIMARY KEY,
    list_id TEXT NOT NULL,
    title TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    priority INTEGER NOT NULL DEFAULT 1,
    due_at INTEGER,
    remind_at INTEGER,
    reminder_channels INTEGER NOT NULL DEFAULT 0,
    done INTEGER NOT NULL DEFAULT 0,
    done_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    device_id TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_items_list ON todo_items (list_id);
CREATE INDEX IF NOT EXISTS idx_items_remind ON todo_items (remind_at);
CREATE INDEX IF NOT EXISTS idx_items_updated ON todo_items (updated_at);
CREATE INDEX IF NOT EXISTS idx_lists_updated ON todo_lists (updated_at);
