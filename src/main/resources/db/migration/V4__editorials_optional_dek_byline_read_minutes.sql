-- TrackEditorialRequest doesn't carry readMinutes, and Album/ArtistEditorialRequest
-- both leave dek/byline optional — upsertTrackEditorial() was passing nulls into
-- NOT NULL columns, so any new track editorial insert would fail. The shared
-- `editorials` table can't enforce a constraint that only some owner types need
-- (Track now requires dek/byline at the request-validation layer instead, see
-- TrackEditorialRequest's @NotBlank).
ALTER TABLE editorials ALTER COLUMN dek DROP NOT NULL;
ALTER TABLE editorials ALTER COLUMN byline DROP NOT NULL;
ALTER TABLE editorials ALTER COLUMN read_minutes DROP NOT NULL;
