-- Replaces editorials.featurated (see V25) — "featured" is now specifically
-- an album concept: the archive hero derives its editorial from the
-- featured album (EditorialService.getFeatured), instead of an independent
-- flag settable on any editorial subtype. Same "at most one at a time"
-- invariant editorials.featurated used to have (see V18), just scoped here.
ALTER TABLE albums ADD COLUMN featured boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX idx_albums_only_one_featured
    ON albums (featured) WHERE featured = true;
