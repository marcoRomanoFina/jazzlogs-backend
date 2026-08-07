-- Record label. Backfilled with a placeholder for the 1 existing row (dev
-- data) since there's no real value to derive it from — update it via the
-- normal POST /albums upsert once a real label is known.
ALTER TABLE albums ADD COLUMN label character varying(255);
UPDATE albums SET label = 'UNKNOWN' WHERE label IS NULL;
ALTER TABLE albums ALTER COLUMN label SET NOT NULL;
