-- Editorial titles are meant to be globally unique — never reused across
-- album/track/artist editorials, even across different owners. See
-- EditorialService's upsert* methods, which catch the resulting
-- DataIntegrityViolationException and turn it into a clean 409 instead of
-- a raw 500.
ALTER TABLE editorials ADD CONSTRAINT uk_editorials_title UNIQUE (title);
