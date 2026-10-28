-- Notes gained a short title (separate from the free-form text body) — see
-- Note.title. Backfilled with a placeholder for existing dev rows, same
-- shape as V7's album label backfill.
ALTER TABLE notes ADD COLUMN title character varying(255);
UPDATE notes SET title = 'Untitled' WHERE title IS NULL;
ALTER TABLE notes ALTER COLUMN title SET NOT NULL;
