-- Which narrator voice reads this series — see Series.voice. Backfilled to
-- MARK for whatever series already exist; new ones set it explicitly via
-- the normal POST/PUT /series upsert.
ALTER TABLE series ADD COLUMN voice character varying(255);
UPDATE series SET voice = 'MARK' WHERE voice IS NULL;
ALTER TABLE series ALTER COLUMN voice SET NOT NULL;
