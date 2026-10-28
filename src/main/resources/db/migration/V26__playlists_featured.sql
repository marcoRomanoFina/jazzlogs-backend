-- Singleton "featured playlist" slot, same "at most one at a time, DB-enforced"
-- pattern as albums.featured (see V24).
ALTER TABLE playlists ADD COLUMN featured boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX idx_playlists_only_one_featured
    ON playlists (featured) WHERE featured = true;
