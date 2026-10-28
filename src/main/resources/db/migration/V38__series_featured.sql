-- Singleton "featured series" slot, same "at most one at a time, DB-enforced"
-- pattern as playlists.featured (see V26) / albums.featured (see V24).
ALTER TABLE series ADD COLUMN featured boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX idx_series_only_one_featured
    ON series (featured) WHERE featured = true;
