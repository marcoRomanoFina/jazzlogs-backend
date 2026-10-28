-- Admin-curated accent color for the album page, persisted so every viewer
-- sees the same one instead of each browser resampling the cover image —
-- see Album.coverColor. Nullable and unbackfilled on purpose: existing
-- albums fall back to the frontend's automatic sampling until an admin sets
-- one explicitly via PUT /albums/{id}/cover-color.
ALTER TABLE albums ADD COLUMN cover_color character varying(7);
