-- Admin-curated text/letter color for the album page, same reasoning as
-- V22's cover_color — persisted so every viewer sees the same one instead
-- of each browser resampling. Nullable and unbackfilled on purpose: existing
-- albums fall back to the frontend's own default until an admin sets one
-- explicitly via PUT /albums/{id}/letter-color.
ALTER TABLE albums ADD COLUMN letter_color character varying(7);
