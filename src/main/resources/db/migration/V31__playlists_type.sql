-- Classification only, for now — see Playlist.type. Backfilled to STANDARD
-- for whatever playlists already exist; new ones set it explicitly via the
-- normal POST/PUT /playlists upsert.
ALTER TABLE playlists ADD COLUMN type character varying(255);
UPDATE playlists SET type = 'STANDARD' WHERE type IS NULL;
ALTER TABLE playlists ALTER COLUMN type SET NOT NULL;
