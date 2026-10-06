-- Preserve every old queue row, including its exact bytes and pending revision.
ALTER TABLE outbox RENAME TO legacy_outbox_v1;
CREATE TABLE outbox (id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, body BLOB NOT NULL);
-- The new protocol sends a fresh snapshot without marking old work acknowledged.
INSERT OR REPLACE INTO meta(key,value)
SELECT 'revision', CAST(MAX(
    COALESCE((SELECT CAST(value AS INTEGER) FROM meta WHERE key='revision'),0),
    COALESCE((SELECT MAX(revision) FROM legacy_outbox_v1),0),
    COALESCE((SELECT CAST(value AS INTEGER) FROM meta WHERE key='acked_revision'),0)
) + 1 AS TEXT);
