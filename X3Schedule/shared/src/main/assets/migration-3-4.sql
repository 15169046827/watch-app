-- Keep protocol-2 outbox rows intact, and queue protocol-3 from authoritative local data.
CREATE TABLE batch_out (slot INTEGER PRIMARY KEY CHECK(slot=1), manifest TEXT NOT NULL, next INTEGER NOT NULL CHECK(next>=0));
CREATE TABLE batch_out_parts (seq INTEGER PRIMARY KEY CHECK(seq>=0 AND seq<256), body BLOB NOT NULL CHECK(length(body)>0 AND length(body)<=131072));
CREATE TABLE batch_in (slot INTEGER PRIMARY KEY CHECK(slot=1), manifest TEXT NOT NULL);
CREATE TABLE batch_in_parts (seq INTEGER PRIMARY KEY CHECK(seq>=0 AND seq<256), body BLOB NOT NULL CHECK(length(body)>0 AND length(body)<=131072), digest TEXT NOT NULL);
CREATE TABLE batch_receipts (id TEXT PRIMARY KEY NOT NULL, manifest TEXT NOT NULL, digests TEXT NOT NULL);
INSERT OR REPLACE INTO meta(key,value)
SELECT 'revision', CAST(MAX(
 COALESCE((SELECT CAST(value AS INTEGER) FROM meta WHERE key='revision'),0),
 COALESCE((SELECT MAX(revision) FROM outbox),0),
 COALESCE((SELECT CAST(value AS INTEGER) FROM meta WHERE key='acked_revision'),0)
) + 1 AS TEXT);
