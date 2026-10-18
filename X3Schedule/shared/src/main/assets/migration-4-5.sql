-- Rebuild unfinished transfers with schema 4 from authoritative data.
DELETE FROM batch_out_parts;
DELETE FROM batch_out;
DELETE FROM batch_in_parts;
DELETE FROM batch_in;
DELETE FROM outbox;
UPDATE meta SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT) WHERE key='revision';
