-- Existing definitions/events keep null progress: their historical scoring semantics stay unchanged.
-- JSON bodies carry versioned progress snapshots; rebuild protocol-4 transfers from authoritative rows.
DELETE FROM batch_out_parts;
DELETE FROM batch_out;
DELETE FROM batch_in_parts;
DELETE FROM batch_in;
DELETE FROM outbox;
UPDATE meta SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT) WHERE key='revision';
